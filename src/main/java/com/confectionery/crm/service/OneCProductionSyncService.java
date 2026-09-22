package com.confectionery.crm.service;

import com.confectionery.crm.domain.Ingredient;
import com.confectionery.crm.domain.Product;
import com.confectionery.crm.domain.ProductionRequest;
import com.confectionery.crm.domain.ProductionRequestItem;
import com.confectionery.crm.domain.ProductionRequestLine;
import com.confectionery.crm.domain.Role;
import com.confectionery.crm.repository.IngredientRepository;
import com.confectionery.crm.repository.ProductRepository;
import com.confectionery.crm.repository.ProductionRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Заявка на производство выгружается в 1С сразу при создании (см.
 * ProductionRequestService.create) парой связанных документов — ровно так же, как это устроено
 * в самой 1С у этого клиента (проверено на 316 реальных документах): "Отчёт производства за
 * смену" (готовая продукция, "Продукция") и "Требование-накладная" (списание сырья,
 * "Материалы"), вторая ссылается на первую через ДокументОснование. Раньше здесь по ошибке
 * использовалось общее "Оприходование товаров" — оно для производства не подходит (не списывает
 * сырьё и не формирует правильную себестоимость), для него теперь отдельный сервис
 * {@link OneCReceiptSyncService} под ручные складские корректировки без производства.
 *
 * <p>До полного оприходования количество продукции в документе — плановое (см.
 * {@link ProductionRequestLine#getPlannedQuantity()}, оценка по рецепту), а не факт. Любое
 * изменение состава заявки, пока она ещё не собрана (редактирование, замена сырья — см.
 * ProductionRequestService.update/substituteItem), и оприходование каждой строки (см.
 * ProductionRequestService.receiveLine) перезаписывают "Продукция"/"Материалы" в уже созданных
 * документах (PATCH), а не создают вторую пару — но только пока документ не проведён
 * бухгалтером в 1С; если уже проведён, менять его состав через OData не проведя заново — значит
 * молча разойтись с фактическими проводками, поэтому в этом случае синхронизация
 * останавливается с понятной ошибкой, и правки в проведённый документ бухгалтер вносит в 1С
 * вручную. Когда оприходована последняя строка, в документе оказывается уже настоящий факт
 * (см. {@link ProductionRequestLine#getReceivedQuantity()}).
 *
 * <p>Дата документа ({@code Date}) в обоих — {@link ProductionRequest#getDocumentDate()} (по
 * умолчанию сегодня, пользователь может выбрать другую при создании/редактировании заявки) с
 * ВСЕГДА фиксированным временем 06:00 (начало смены) — так принято в этой базе 1С.
 *
 * <p>Оба документа создаются НЕпроведёнными и остаются такими со стороны CRM: "Плановая
 * себестоимость" каждой строки продукции берётся из типа цен "Плановая" в 1С ({@code
 * crm.onec.planned-price-type-key}, см. {@link #fetchPlannedPrices}) — 0, если для номенклатуры
 * в этом типе цен ничего не заведено (тогда бухгалтер вписывает её вручную при проверке, как и
 * раньше). Отчёт производства за смену CRM не проводит вообще (раньше проводился автоматически
 * сразу при полном оприходовании заявки — убрано по той же причине, что и автопроведение
 * заказов: Document.Posted=true через OData оказался ненадёжен, 1С отвечала 200 OK, но реальных
 * движений по складу не формировала, пока документ не открывали в самой 1С и не нажимали
 * "Провести" вручную) — бухгалтер проводит его в 1С сам. Требование-накладную (списание сырья)
 * так же не проводим автоматически — во время производства состав сырья ещё может понадобиться
 * поправить (см. class-javadoc у ProductionRequestService.substituteItem: ADMIN и WAREHOUSE_RAW
 * могут менять его в любом статусе заявки, кроме отменённой), её проводит вручную кнопка
 * "Завершить" (см. {@link #postRequisition}), когда состав сырья точно больше не изменится.
 *
 * <p>Best-effort — заявка в CRM уже сохранена/оприходована и не откатывается, если 1С
 * недоступна или что-то не сопоставилось; причина пишется в {@link ProductionRequest#getOneCSyncError()}
 * и уходит уведомлением админу.
 *
 * <p>Если в позиции было заменено сырьё (см. {@link ProductionRequestItem#getOriginalIngredientName()}),
 * требование-накладная списывает уже новое сырьё (реальный факт), а в её "Комментарий" (только там,
 * не в отчёте производства) добавляется "Замена сырья: X вместо Y" — чтобы бухгалтер видел причину
 * отклонения от нормы спецификации, не заходя в CRM.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OneCProductionSyncService {

    private static final DateTimeFormatter ONEC_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final String ZERO_GUID = "00000000-0000-0000-0000-000000000000";
    private static final String UNDEFINED_TYPE = "StandardODATA.Undefined";

    private final OneCClient oneCClient;
    private final ProductionRequestRepository requestRepository;
    private final ProductRepository productRepository;
    private final IngredientRepository ingredientRepository;
    private final NotificationService notificationService;

    @Value("${crm.onec.organization-key:}")
    private String organizationKey;
    @Value("${crm.onec.tax-accounting-type-key:}")
    private String taxAccountingTypeKey;
    @Value("${crm.onec.finished-goods-warehouse-key:d2791a64-4f43-11f1-9670-5254007c5efd}")
    private String finishedGoodsWarehouseKey;
    @Value("${crm.onec.materials-warehouse-key:ca2e388a-4f43-11f1-9670-5254007c5efd}")
    private String materialsWarehouseKey;
    @Value("${crm.onec.finished-goods-account-bu-key:}")
    private String finishedGoodsAccountBuKey;
    @Value("${crm.onec.finished-goods-account-nu-key:}")
    private String finishedGoodsAccountNuKey;
    @Value("${crm.onec.materials-account-bu-key:}")
    private String materialsAccountBuKey;
    @Value("${crm.onec.production-cost-account-bu-key:}")
    private String productionCostAccountBuKey;
    @Value("${crm.onec.production-cost-account-nu-key:}")
    private String productionCostAccountNuKey;
    @Value("${crm.onec.production-department-key:}")
    private String productionDepartmentKey;
    @Value("${crm.onec.production-cost-item-key:}")
    private String productionCostItemKey;
    @Value("${crm.onec.planned-price-type-key:}")
    private String plannedPriceTypeKey;
    @Value("${crm.onec.default-nomenclature-group-key:}")
    private String defaultNomenclatureGroupKey;

    /**
     * Никогда не бросает исключение наружу — вызывается при создании заявки, при любом
     * изменении её состава до сборки (редактирование, замена сырья) и при оприходовании каждой
     * строки. Сам решает, создавать документы в 1С или обновлять уже существующие (см.
     * класс-javadoc).
     */
    @Transactional
    public void trySyncRequest(ProductionRequest request) {
        if (!oneCClient.isConfigured()) {
            return;
        }
        try {
            sync(request);
        } catch (Exception e) {
            onSyncFailure(request, e);
        }
    }

    /**
     * То же самое, что {@link #trySyncRequest}, но для ручной повторной попытки (см.
     * ProductionRequestService.resyncOneC) — бросает исключение вместо тихого логирования,
     * если 1С не настроена или синхронизация не удалась, чтобы пользователь увидел причину.
     */
    @Transactional
    public void syncOrThrow(ProductionRequest request) {
        if (!oneCClient.isConfigured()) {
            throw new IllegalStateException("Подключение к 1С не настроено (crm.onec.base-url/username/password)");
        }
        try {
            sync(request);
        } catch (Exception e) {
            onSyncFailure(request, e);
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private void onSyncFailure(ProductionRequest request, Exception e) {
        log.warn("Не удалось синхронизировать заявку №{} с 1С: {}", request.getId(), e.getMessage());
        request.setOneCSyncError(e.getMessage());
        requestRepository.save(request);
        notificationService.notifyRole(Role.ADMIN,
                "Заявка №" + request.getId() + " «" + request.getTitle() + "» не синхронизирована с 1С: " + e.getMessage(), null);
    }

    /**
     * Вызывается при удалении заявки (см. ProductionRequestService.delete) — если по ней уже
     * создавались документы в 1С, помечает их там на удаление (DeletionMark, не физическое
     * удаление — окончательно убрать сможет только сама 1С, со всеми её проверками на
     * зависимые движения). Best-effort: недоступность 1С не блокирует удаление заявки в CRM.
     */
    public void tryMarkForDeletion(ProductionRequest request) {
        if (!oneCClient.isConfigured()) {
            return;
        }
        unpostAndMark(request, "Document_ТребованиеНакладная", request.getOneCRequisitionKey(), "требование-накладную");
        unpostAndMark(request, "Document_ОтчетПроизводстваЗаСмену", request.getOneCReportKey(), "отчёт производства");
    }

    /**
     * Сначала распроводим, потом помечаем на удаление: проведённый документ продолжает держать
     * движения по складу, и пометка удаления их не снимает — а отмена заявки как раз и должна
     * откатить всё, что заявка сделала. Отчёт производства к этому моменту может быть проведён
     * автоматически ({@link #postReport}), поэтому шаг обязателен.
     */
    private void unpostAndMark(ProductionRequest request, String entitySet, String ref, String what) {
        if (ref == null) {
            return;
        }
        try {
            if (Boolean.TRUE.equals(oneCClient.fetchPosted(entitySet, ref))) {
                oneCClient.unpostDocument(entitySet, ref);
                log.info("Заявка №{} — {} распроведена в 1С перед пометкой на удаление", request.getId(), what);
            }
            oneCClient.markForDeletion(entitySet, ref, true);
        } catch (Exception e) {
            log.warn("Не удалось пометить {} на удаление в 1С (заявка №{}): {}", what, request.getId(), e.getMessage());
        }
    }

    /**
     * Создаёт документы в 1С (если заявка выгружается впервые) или перезаписывает "Продукция"/
     * "Материалы" в уже созданных (если состав/факт изменился с прошлой выгрузки) — см.
     * класс-javadoc. Количество продукции — факт по строке, если она уже оприходована, иначе
     * план по рецепту (см. {@link ProductionRequestLine#isReceived()}); количество сырья —
     * всегда {@link ProductionRequestItem#getRequiredQuantity()} (снимок на текущий момент,
     * учитывающий возможную замену сырья).
     */
    private void sync(ProductionRequest request) {
        Product firstProduct = request.getLines().get(0).getProduct();
        // Полуфабрикат (см. Product.semiFinished, например "Пряник") физически хранится и
        // списывается как сырьё — "Продукция" отчёта производства уходит на склад/счёт сырья,
        // а не готовой продукции. Заявка предполагается однородной (все строки одного вида —
        // либо обычные товары, либо полуфабрикаты), поэтому решаем по первой строке.
        boolean semiFinishedOutput = firstProduct.isSemiFinished();
        String firstNomenclatureKey = resolveProductKey(firstProduct);
        if (firstNomenclatureKey == null) {
            throw new IllegalStateException("Не найдено в 1С по названию: " + firstProduct.getName());
        }
        // Если у самого товара в 1С номенклатурная группа не задана (бывает — см.
        // fetchNomenclatureGroup) и запасной вариант тоже не настроен, только тогда сдаёмся с
        // понятной ошибкой; иначе используем "Основную номенклатурную группу" (или что
        // настроено в crm.onec.default-nomenclature-group-key) вместо того, чтобы блокировать
        // выгрузку всей заявки из-за одной незаполненной карточки товара в 1С.
        String groupKey = oneCClient.fetchNomenclatureGroup(firstNomenclatureKey);
        if (groupKey == null) {
            if (defaultNomenclatureGroupKey.isBlank()) {
                throw new IllegalStateException("У товара «" + firstProduct.getName() + "» не задана номенклатурная группа в 1С");
            }
            groupKey = defaultNomenclatureGroupKey;
        }
        // Подразделение (Производство Корпус А/B/С/D) — по товару, если оно уже импортировано из
        // истории реальных документов (см. OneCDepartmentImportService), иначе запасной вариант —
        // единая константа из конфигурации, как было раньше. Заявка предполагается однородной
        // (см. semiFinishedOutput/groupKey выше), поэтому решаем по первой строке.
        String departmentKey = firstProduct.getOneCDepartmentKey() != null
                ? firstProduct.getOneCDepartmentKey() : productionDepartmentKey;

        String comment = "Заявка на производство №" + request.getId() + " «" + request.getTitle() + "»";
        // Время в обоих документах всегда фиксированное 06:00 (начало смены) — так принято в
        // этой базе 1С, дату документа выбирает пользователь при создании/редактировании заявки
        // (см. ProductionRequest.documentDate), а не берётся текущее время синхронизации.
        String date = ONEC_DATE.format(request.getDocumentDate().atTime(6, 0));

        Map<String, BigDecimal> plannedPrices = fetchPlannedPrices();

        List<Map<String, Object>> productionLines = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        int lineNumber = 1;
        for (ProductionRequestLine line : request.getLines()) {
            String key = resolveProductKey(line.getProduct());
            if (key == null) {
                unresolved.add(line.getProduct().getName());
                continue;
            }
            // Группа берётся по номенклатуре ИМЕННО этой строки — заявка обычно однородна (см.
            // groupKey выше), но если для конкретного товара группу в 1С определить не удалось,
            // подстраховываемся уже проверенной групповой для шапки, а не молчим нулевым GUID.
            String lineGroupKey = oneCClient.fetchNomenclatureGroup(key);
            if (lineGroupKey == null) lineGroupKey = groupKey;
            BigDecimal quantity = line.isReceived() ? line.getReceivedQuantity() : line.getPlannedQuantity();
            BigDecimal plannedPrice = plannedPrices.getOrDefault(key, BigDecimal.ZERO);
            String specificationKey = line.getProduct().getOneCSpecificationKey();

            Map<String, Object> l = new LinkedHashMap<>();
            l.put("LineNumber", String.valueOf(lineNumber++));
            l.put("ЕдиницаИзмерения_Key", unitOrZero(key));
            l.put("Количество", quantity);
            l.put("Коэффициент", 1);
            l.put("Номенклатура_Key", key);
            l.put("НоменклатурнаяГруппа_Key", lineGroupKey);
            l.put("СчетБУ_Key", semiFinishedOutput ? materialsAccountBuKey : finishedGoodsAccountBuKey);
            l.put("СчетНУ_Key", semiFinishedOutput ? ZERO_GUID : finishedGoodsAccountNuKey);
            l.put("СчетЗатрат_Key", ZERO_GUID);
            l.put("ПлановаяСтоимость", plannedPrice);
            l.put("СуммаПлановая", plannedPrice.multiply(quantity));
            l.put("Спецификация_Key", specificationKey != null ? specificationKey : ZERO_GUID);
            l.put("НомерГТД_Key", ZERO_GUID);
            productionLines.add(l);
        }
        if (!unresolved.isEmpty()) {
            throw new IllegalStateException("Не найдено в 1С по названию: " + String.join(", ", unresolved));
        }

        List<Map<String, Object>> materialLines = new ArrayList<>();
        List<String> unresolvedMaterials = new ArrayList<>();
        int materialLineNumber = 1;
        for (ProductionRequestItem item : request.getItems()) {
            String key = resolveIngredientKey(item.getIngredient());
            if (key == null) {
                unresolvedMaterials.add(item.getIngredient().getName());
                continue;
            }
            Map<String, Object> l = new LinkedHashMap<>();
            l.put("LineNumber", String.valueOf(materialLineNumber++));
            l.put("Номенклатура_Key", key);
            l.put("СчетБУ_Key", materialsAccountBuKey);
            l.put("СчетНУ_Key", ZERO_GUID);
            l.put("ЕдиницаИзмерения_Key", unitOrZero(key));
            l.put("Коэффициент", 1);
            l.put("Количество", item.getRequiredQuantity());
            materialLines.add(l);
        }
        if (!unresolvedMaterials.isEmpty()) {
            throw new IllegalStateException("Не найдено в 1С по названию (сырьё): " + String.join(", ", unresolvedMaterials));
        }

        // Отчёт производства — создаём или обновляем; дальше требование-накладная ссылается на его Ref_Key.
        Map<String, Object> reportBody = new LinkedHashMap<>();
        reportBody.put("Date", date);
        reportBody.put("Posted", false);
        reportBody.put("Организация_Key", organizationKey);
        reportBody.put("Склад_Key", semiFinishedOutput ? materialsWarehouseKey : finishedGoodsWarehouseKey);
        reportBody.put("ВидУчетаНУ_Key", taxAccountingTypeKey);
        reportBody.put("СчетЗатратБУ_Key", productionCostAccountBuKey);
        reportBody.put("СчетЗатратНУ_Key", productionCostAccountNuKey);
        reportBody.put("ПодразделениеОрганизации_Key", departmentKey);
        reportBody.put("НоменклатурнаяГруппа_Key", groupKey);
        reportBody.put("Комментарий", comment);
        reportBody.put("Продукция", productionLines);

        String reportRef;
        String reportNumber;
        if (request.getOneCReportKey() == null) {
            OneCClient.CreatedDocument report = oneCClient.createProductionReport(reportBody);
            reportRef = report.ref();
            reportNumber = report.number();
            request.setOneCReportKey(reportRef);
            request.setOneCReportNumber(reportNumber);
            requestRepository.save(request);
            log.info("Заявка №{} — создан непроведённый отчёт производства №{} в 1С", request.getId(), reportNumber);
        } else {
            reportRef = request.getOneCReportKey();
            reportNumber = request.getOneCReportNumber();
            if (Boolean.TRUE.equals(oneCClient.fetchPosted("Document_ОтчетПроизводстваЗаСмену", reportRef))) {
                // Проведённый отчёт не трогаем, но и синхронизацию из-за него не обрываем (раньше
                // здесь была ошибка — она заодно оставляла без обновления требование-накладную
                // ниже). Его состав с этого момента и не может измениться: позиции продукции
                // после полного оприходования редактировать нельзя (см.
                // ProductionRequestService.update), а правки сырья едут в требование-накладную.
                log.info("Заявка №{} — отчёт производства №{} уже проведён в 1С, состав не меняем",
                        request.getId(), reportNumber);
            } else {
                oneCClient.updateDocument("Document_ОтчетПроизводстваЗаСмену", reportRef, reportBody);
                log.info("Заявка №{} — обновлён состав непроведённого отчёта производства №{} в 1С", request.getId(), reportNumber);
            }
        }

        List<String> substitutions = new ArrayList<>();
        for (ProductionRequestItem item : request.getItems()) {
            if (item.getOriginalIngredientName() != null) {
                substitutions.add(item.getIngredient().getName() + " вместо " + item.getOriginalIngredientName());
            }
        }
        String requisitionComment = comment;
        if (!substitutions.isEmpty()) {
            requisitionComment = comment + ". Замена сырья: " + String.join("; ", substitutions) + ".";
        }

        Map<String, Object> requisitionBody = new LinkedHashMap<>();
        requisitionBody.put("Date", date);
        requisitionBody.put("Posted", false);
        requisitionBody.put("Организация_Key", organizationKey);
        requisitionBody.put("Склад_Key", materialsWarehouseKey);
        requisitionBody.put("ВидУчетаНУ_Key", taxAccountingTypeKey);
        requisitionBody.put("СчетЗатратБУ_Key", productionCostAccountBuKey);
        requisitionBody.put("СчетЗатратНУ_Key", ZERO_GUID);
        requisitionBody.put("СубконтоЗатратБУ1", departmentKey);
        requisitionBody.put("СубконтоЗатратБУ1_Type", "StandardODATA.Catalog_ПодразделенияОрганизаций");
        requisitionBody.put("СубконтоЗатратБУ2", groupKey);
        requisitionBody.put("СубконтоЗатратБУ2_Type", "StandardODATA.Catalog_НоменклатурныеГруппы");
        requisitionBody.put("СубконтоЗатратБУ3", productionCostItemKey);
        requisitionBody.put("СубконтоЗатратБУ3_Type", "StandardODATA.Catalog_СтатьиЗатрат");
        requisitionBody.put("СубконтоЗатратНУ1", ZERO_GUID);
        requisitionBody.put("СубконтоЗатратНУ1_Type", UNDEFINED_TYPE);
        requisitionBody.put("СубконтоЗатратНУ2", ZERO_GUID);
        requisitionBody.put("СубконтоЗатратНУ2_Type", UNDEFINED_TYPE);
        requisitionBody.put("СубконтоЗатратНУ3", ZERO_GUID);
        requisitionBody.put("СубконтоЗатратНУ3_Type", UNDEFINED_TYPE);
        requisitionBody.put("ДокументОснование", reportRef);
        requisitionBody.put("ДокументОснование_Type", "StandardODATA.Document_ОтчетПроизводстваЗаСмену");
        requisitionBody.put("Номенклатура_Key", ZERO_GUID);
        requisitionBody.put("Контрагент_Key", ZERO_GUID);
        requisitionBody.put("Комментарий", requisitionComment);
        requisitionBody.put("Материалы", materialLines);

        if (request.getOneCRequisitionKey() == null) {
            OneCClient.CreatedDocument requisition = oneCClient.createMaterialsRequisition(requisitionBody);
            request.setOneCRequisitionKey(requisition.ref());
            request.setOneCRequisitionNumber(requisition.number());
            request.setOneCSyncError(null);
            requestRepository.save(request);
            log.info("Заявка №{} — создана непроведённая требование-накладная №{} в 1С (основание: отчёт №{})",
                    request.getId(), requisition.number(), reportNumber);
        } else {
            if (Boolean.TRUE.equals(oneCClient.fetchPosted("Document_ТребованиеНакладная", request.getOneCRequisitionKey()))) {
                throw new IllegalStateException("Требование-накладная №" + request.getOneCRequisitionNumber()
                        + " уже проведена в 1С — корректировку внесите туда вручную");
            }
            oneCClient.updateDocument("Document_ТребованиеНакладная", request.getOneCRequisitionKey(), requisitionBody);
            request.setOneCSyncError(null);
            requestRepository.save(request);
            log.info("Заявка №{} — обновлён состав непроведённой требование-накладной №{} в 1С",
                    request.getId(), request.getOneCRequisitionNumber());
        }
    }

    /**
     * Плановая себестоимость по номенклатуре — из типа цен "Плановая" в 1С (регистр цен
     * номенклатуры, тот же механизм, что и именные типы цен клиентов, см.
     * OneCClientPriceSyncService.sync): регистр периодический, на каждую номенклатуру берём
     * самую свежую АКТИВНУЮ запись ({@code period} сравнивается как строка — ISO-формат из 1С,
     * лексикографический порядок совпадает с хронологическим). Пустая карта, если
     * {@code crm.onec.planned-price-type-key} не задан или по этому типу цен в 1С ещё вообще
     * ничего не заведено — тогда строки уходят с ПлановаяСтоимость:0, как и раньше.
     */
    private Map<String, BigDecimal> fetchPlannedPrices() {
        if (plannedPriceTypeKey.isBlank()) {
            return Map.of();
        }
        Map<String, OneCClient.PriceRow> latestByNomenclature = new HashMap<>();
        for (OneCClient.PriceRow row : oneCClient.fetchPricesByType(plannedPriceTypeKey)) {
            if (!row.active()) continue;
            OneCClient.PriceRow current = latestByNomenclature.get(row.nomenclatureKey());
            if (current == null || row.period().compareTo(current.period()) > 0) {
                latestByNomenclature.put(row.nomenclatureKey(), row);
            }
        }
        Map<String, BigDecimal> prices = new HashMap<>();
        latestByNomenclature.forEach((key, row) -> prices.put(key, row.price()));
        return prices;
    }

    private String unitOrZero(String nomenclatureKey) {
        String unit = oneCClient.fetchNomenclatureUnit(nomenclatureKey);
        return unit == null ? ZERO_GUID : unit;
    }

    private String resolveProductKey(Product product) {
        if (product.getOneCNomenclatureKey() != null) {
            return product.getOneCNomenclatureKey();
        }
        String key = findNomenclatureKeyByName(product.getName());
        if (key == null) return null;
        product.setOneCNomenclatureKey(key);
        productRepository.save(product);
        return key;
    }

    private String resolveIngredientKey(Ingredient ingredient) {
        if (ingredient.getOneCNomenclatureKey() != null) {
            return ingredient.getOneCNomenclatureKey();
        }
        String key = findNomenclatureKeyByName(ingredient.getName());
        if (key == null) return null;
        ingredient.setOneCNomenclatureKey(key);
        ingredientRepository.save(ingredient);
        return key;
    }

    private String findNomenclatureKeyByName(String name) {
        String needle = name.trim().toLowerCase();
        return oneCClient.fetchNomenclatureNames().entrySet().stream()
                .filter(e -> e.getValue().trim().toLowerCase().equals(needle))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    /**
     * "Завершить" — вручную проводит требование-накладную (списание сырья) в 1С, когда состав
     * заявки точно больше не изменится (вызывается кнопкой на карточке заявки, см. web/mobile
     * контроллеры). Best-effort, как и остальная синхронизация (2026-09-22, по просьбе
     * пользователя: "Завершить"/"Отгрузил"/"Собрано" не должны блокироваться из-за 1С — источник
     * истины по остаткам мы и так читаем ИЗ 1С отдельным опросом, а не выводим из этих действий;
     * раньше ошибка здесь не давала завершить заявку в CRM вообще). Причина неудачи — в
     * {@link ProductionRequest#getOneCSyncError()} и уведомлением ADMIN, действие в CRM не
     * блокируется. Уже проведённый документ (кто-то провёл вручную в 1С раньше) — не ошибка,
     * цель и так достигнута.
     */
    /**
     * Проводит "Отчёт производства за смену" — автоматически, как только заявка оприходована
     * ЦЕЛИКОМ (см. ProductionRequestService.receiveLine): к этому моменту фактический выход
     * продукции известен полностью, и проведение ставит её на склад в 1С. Требование-накладную
     * (списание сырья) здесь не трогаем — её проводит кнопка "Завершить"
     * ({@link #postRequisition}), потому что состав сырья после оприходования ещё уточняют.
     *
     * <p>Best-effort, как и остальная синхронизация: оприходование в CRM из-за 1С не падает,
     * причина неудачи — в {@link ProductionRequest#getOneCSyncError()} и уведомлением ADMIN.
     * Уже проведённый отчёт (бухгалтер успел вручную) — не ошибка, цель достигнута.
     */
    @Transactional
    public void postReport(ProductionRequest request) {
        if (!oneCClient.isConfigured() || request.getOneCReportKey() == null) {
            return;
        }
        try {
            if (Boolean.TRUE.equals(oneCClient.fetchPosted("Document_ОтчетПроизводстваЗаСмену", request.getOneCReportKey()))) {
                return;
            }
            oneCClient.postDocument("Document_ОтчетПроизводстваЗаСмену", request.getOneCReportKey());
            request.setOneCSyncError(null);
            requestRepository.save(request);
            log.info("Заявка №{} — отчёт производства №{} проведён в 1С (заявка оприходована полностью)",
                    request.getId(), request.getOneCReportNumber());
        } catch (Exception e) {
            String message = "отчёт производства №" + request.getOneCReportNumber()
                    + " не проведён в 1С: " + e.getMessage();
            log.warn("Заявка №{} — {}", request.getId(), message);
            request.setOneCSyncError(message);
            requestRepository.save(request);
            notificationService.notifyRole(Role.ADMIN,
                    "Заявка №" + request.getId() + " «" + request.getTitle() + "» — " + message, null);
        }
    }

    @Transactional
    public void postRequisition(ProductionRequest request) {
        if (!oneCClient.isConfigured()) {
            return;
        }
        try {
            if (request.getOneCRequisitionKey() == null) {
                throw new IllegalStateException("Требование-накладная ещё не выгружена в 1С — сначала синхронизируйте заявку");
            }
            if (Boolean.TRUE.equals(oneCClient.fetchPosted("Document_ТребованиеНакладная", request.getOneCRequisitionKey()))) {
                request.setOneCSyncError(null);
                requestRepository.save(request);
                return;
            }
            oneCClient.postDocument("Document_ТребованиеНакладная", request.getOneCRequisitionKey());
            request.setOneCSyncError(null);
            requestRepository.save(request);
            log.info("Заявка №{} — требование-накладная №{} проведена в 1С вручную ('Завершить')",
                    request.getId(), request.getOneCRequisitionNumber());
        } catch (Exception e) {
            log.warn("Не удалось провести требование-накладную заявки №{} в 1С: {}", request.getId(), e.getMessage());
            request.setOneCSyncError(e.getMessage());
            requestRepository.save(request);
            notificationService.notifyRole(Role.ADMIN,
                    "Заявка №" + request.getId() + " «" + request.getTitle()
                            + "»: не удалось провести требование-накладную в 1С: " + e.getMessage(), null);
        }
    }

    /**
     * "Отменить" — обратное {@link #postRequisition}: распроводит требование-накладную в 1С,
     * когда после "Завершить" нашли ошибку в составе сырья и её нужно снова поправить (см.
     * ProductionRequestService.cancelCompletion — снова разрешает редактировать/удалять/
     * добавлять позиции после успешной распроводки).
     */
    @Transactional
    public void unpostRequisition(ProductionRequest request) {
        if (!oneCClient.isConfigured()) {
            throw new IllegalStateException("Подключение к 1С не настроено (crm.onec.base-url/username/password)");
        }
        if (request.getOneCRequisitionKey() == null) {
            throw new IllegalStateException("Требование-накладная ещё не выгружена в 1С");
        }
        try {
            oneCClient.unpostDocument("Document_ТребованиеНакладная", request.getOneCRequisitionKey());
        } catch (Exception e) {
            request.setOneCSyncError("Не удалось отменить проведение в 1С: " + e.getMessage());
            requestRepository.save(request);
            notificationService.notifyRole(Role.ADMIN,
                    "Заявка №" + request.getId() + " «" + request.getTitle()
                            + "»: не удалось отменить проведение требования-накладной в 1С: " + e.getMessage(), null);
            throw new IllegalStateException(e.getMessage(), e);
        }
        request.setOneCSyncError(null);
        requestRepository.save(request);
        log.info("Заявка №{} — требование-накладная №{} распроведена в 1С вручную ('Отменить')",
                request.getId(), request.getOneCRequisitionNumber());
    }
}
