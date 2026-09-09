package com.confectionery.crm.service;

import com.confectionery.crm.domain.Ingredient;
import com.confectionery.crm.domain.Product;
import com.confectionery.crm.domain.ProductionRequest;
import com.confectionery.crm.domain.ProductionRequestItem;
import com.confectionery.crm.domain.ProductionRequestLine;
import com.confectionery.crm.domain.ProductionRequestStatus;
import com.confectionery.crm.domain.Role;
import com.confectionery.crm.repository.IngredientRepository;
import com.confectionery.crm.repository.ProductRepository;
import com.confectionery.crm.repository.ProductionRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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
 * <p>Оба документа создаются НЕпроведёнными, как и заказы: "Плановая себестоимость" каждой
 * строки продукции всегда 0 (в 1С этого значения взять неоткуда — не заведён отдельный
 * регистр/тип цен под это в данной базе), бухгалтер вписывает её вручную при проверке и потом
 * проводит сам, либо (см. {@link #scheduledPostEligibleRequests}) документ проводится
 * автоматически в 20:00, если бухгалтер не тронул его сам.
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
        if (request.getOneCRequisitionKey() != null) {
            try {
                oneCClient.markForDeletion("Document_ТребованиеНакладная", request.getOneCRequisitionKey(), true);
            } catch (Exception e) {
                log.warn("Не удалось пометить требование-накладную на удаление в 1С (заявка №{}): {}",
                        request.getId(), e.getMessage());
            }
        }
        if (request.getOneCReportKey() != null) {
            try {
                oneCClient.markForDeletion("Document_ОтчетПроизводстваЗаСмену", request.getOneCReportKey(), true);
            } catch (Exception e) {
                log.warn("Не удалось пометить отчёт производства на удаление в 1С (заявка №{}): {}",
                        request.getId(), e.getMessage());
            }
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
        String groupKey = oneCClient.fetchNomenclatureGroup(firstNomenclatureKey);
        if (groupKey == null) {
            throw new IllegalStateException("У товара «" + firstProduct.getName() + "» не задана номенклатурная группа в 1С");
        }

        String comment = "Заявка на производство №" + request.getId() + " «" + request.getTitle() + "»";
        // Время в обоих документах всегда фиксированное 06:00 (начало смены) — так принято в
        // этой базе 1С, дату документа выбирает пользователь при создании/редактировании заявки
        // (см. ProductionRequest.documentDate), а не берётся текущее время синхронизации.
        String date = ONEC_DATE.format(request.getDocumentDate().atTime(6, 0));

        List<Map<String, Object>> productionLines = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        int lineNumber = 1;
        for (ProductionRequestLine line : request.getLines()) {
            String key = resolveProductKey(line.getProduct());
            if (key == null) {
                unresolved.add(line.getProduct().getName());
                continue;
            }
            Map<String, Object> l = new LinkedHashMap<>();
            l.put("LineNumber", String.valueOf(lineNumber++));
            l.put("ЕдиницаИзмерения_Key", unitOrZero(key));
            l.put("Количество", line.isReceived() ? line.getReceivedQuantity() : line.getPlannedQuantity());
            l.put("Коэффициент", 1);
            l.put("Номенклатура_Key", key);
            l.put("НоменклатурнаяГруппа_Key", ZERO_GUID);
            l.put("СчетБУ_Key", semiFinishedOutput ? materialsAccountBuKey : finishedGoodsAccountBuKey);
            l.put("СчетНУ_Key", semiFinishedOutput ? ZERO_GUID : finishedGoodsAccountNuKey);
            l.put("СчетЗатрат_Key", ZERO_GUID);
            l.put("ПлановаяСтоимость", 0);
            l.put("СуммаПлановая", 0);
            l.put("Спецификация_Key", ZERO_GUID);
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
        reportBody.put("ПодразделениеОрганизации_Key", productionDepartmentKey);
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
            if (Boolean.TRUE.equals(oneCClient.fetchPosted("Document_ОтчетПроизводстваЗаСмену", request.getOneCReportKey()))) {
                throw new IllegalStateException("Отчёт производства №" + request.getOneCReportNumber()
                        + " уже проведён в 1С — корректировку внесите туда вручную");
            }
            reportRef = request.getOneCReportKey();
            reportNumber = request.getOneCReportNumber();
            oneCClient.updateDocument("Document_ОтчетПроизводстваЗаСмену", reportRef, reportBody);
            log.info("Заявка №{} — обновлён состав непроведённого отчёта производства №{} в 1С", request.getId(), reportNumber);
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
        requisitionBody.put("СубконтоЗатратБУ1", productionDepartmentKey);
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

    /** Статусы, при которых заявка считается доведённой до конца и её документы в 1С можно проводить. */
    private static final List<ProductionRequestStatus> POSTABLE_STATUSES = List.of(ProductionRequestStatus.RECEIVED);

    /**
     * Раз в сутки в 20:00 (та же настройка, что и у автопроведения заказов — crm.onec.post-cron)
     * проводит в 1С документы полностью оприходованных заявок, если бухгалтер не тронул их
     * сам. Проводим сначала отчёт производства, потом требование-накладную (она на него
     * ссылается) — одна неудача не прерывает обработку остальных заявок в пакете.
     */
    @Scheduled(cron = "${crm.onec.post-cron:0 0 20 * * *}")
    public void scheduledPostEligibleRequests() {
        if (!oneCClient.isConfigured()) {
            return;
        }
        PostResult result = postEligibleRequests();
        if (result.posted() > 0 || !result.failed().isEmpty()) {
            log.info("Автопроведение заявок на производство в 1С: проведено {}, уже было проведено {}, не удалось {}",
                    result.posted(), result.alreadyPosted(), result.failed().size());
        }
        if (!result.failed().isEmpty()) {
            log.warn("Не удалось провести заявки на производство в 1С: {}", result.failed());
        }
    }

    /** Тот же проход, что и по расписанию, но вызываемый вручную — бросает исключение, если 1С не настроена. */
    @Transactional
    public PostResult postEligibleRequests() {
        if (!oneCClient.isConfigured()) {
            throw new IllegalStateException("Подключение к 1С не настроено (crm.onec.base-url/username/password)");
        }
        int posted = 0;
        int alreadyPosted = 0;
        List<String> failed = new ArrayList<>();
        List<ProductionRequest> candidates = requestRepository.findAll().stream()
                .filter(r -> r.getOneCReportKey() != null && r.getOneCRequisitionKey() != null)
                .filter(r -> POSTABLE_STATUSES.contains(r.getStatus()))
                .toList();
        for (ProductionRequest request : candidates) {
            try {
                boolean reportPosted = Boolean.TRUE.equals(
                        oneCClient.fetchPosted("Document_ОтчетПроизводстваЗаСмену", request.getOneCReportKey()));
                boolean requisitionPosted = Boolean.TRUE.equals(
                        oneCClient.fetchPosted("Document_ТребованиеНакладная", request.getOneCRequisitionKey()));
                if (reportPosted && requisitionPosted) {
                    alreadyPosted++;
                    continue;
                }
                if (!reportPosted) {
                    oneCClient.postDocument("Document_ОтчетПроизводстваЗаСмену", request.getOneCReportKey());
                }
                if (!requisitionPosted) {
                    oneCClient.postDocument("Document_ТребованиеНакладная", request.getOneCRequisitionKey());
                }
                request.setOneCSyncError(null);
                requestRepository.save(request);
                posted++;
            } catch (Exception e) {
                log.warn("Не удалось провести документы заявки №{} в 1С: {}", request.getId(), e.getMessage());
                request.setOneCSyncError("Не удалось провести в 1С: " + e.getMessage());
                requestRepository.save(request);
                notificationService.notifyRole(Role.ADMIN,
                        "Заявка №" + request.getId() + " «" + request.getTitle()
                                + "» не удалось провести в 1С: " + e.getMessage(), null);
                failed.add("№" + request.getId() + ": " + e.getMessage());
            }
        }
        return new PostResult(posted, alreadyPosted, failed);
    }

    public record PostResult(int posted, int alreadyPosted, List<String> failed) {
    }
}
