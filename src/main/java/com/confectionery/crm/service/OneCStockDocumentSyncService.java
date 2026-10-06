package com.confectionery.crm.service;

import com.confectionery.crm.domain.Ingredient;
import com.confectionery.crm.domain.Product;
import com.confectionery.crm.domain.Role;
import com.confectionery.crm.domain.StockDocument;
import com.confectionery.crm.domain.StockMovement;
import com.confectionery.crm.domain.StockMovementType;
import com.confectionery.crm.repository.IngredientRepository;
import com.confectionery.crm.repository.ProductRepository;
import com.confectionery.crm.repository.StockDocumentRepository;
import com.confectionery.crm.repository.StockMovementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ручные документы склада (StockDocument — форма «Поступление»/«Списание» в приложении) → 1С:
 * «Оприходование товаров» / «Списание товаров», сразу проведённые. Товар — склад ГП и счёт
 * 1320, сырьё и полуфабрикаты — склад материалов и счёт 1310. Счета, субконто и виды
 * НДС сняты с документов, которые бухгалтер делает в этой базе вручную (проверено 06.10 по
 * всем 14 «Списаниям» и 29 «Оприходованиям»): у списания счёт затрат один, субконто 2 —
 * номенклатурная группа; у оприходования кор. счёт «прочие доходы», субконто НУ 2 — сама
 * номенклатура.
 *
 * <p>Вызывается только фоновой очередью (OneCQueue.stockDocument) — «привести документ 1С к
 * CRM»: нет документа — создать и провести; есть — распровести, перезаписать, провести; в
 * CRM не осталось строк — распровести и пометить на удаление.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OneCStockDocumentSyncService {

    private static final String WRITE_OFF = "Document_СписаниеТоваров";
    private static final String RECEIPT = "Document_ОприходованиеТоваров";
    private static final String ZERO_GUID = "00000000-0000-0000-0000-000000000000";
    private static final DateTimeFormatter ONEC_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final OneCClient oneCClient;
    private final StockDocumentRepository documentRepository;
    private final StockMovementRepository movementRepository;
    private final ProductRepository productRepository;
    private final IngredientRepository ingredientRepository;
    private final IngredientPriceService ingredientPriceService;
    private final NotificationService notificationService;

    @Value("${crm.onec.organization-key:}")
    private String organizationKey;
    @Value("${crm.onec.tax-accounting-type-key:}")
    private String taxAccountingTypeKey;
    @Value("${crm.onec.finished-goods-warehouse-key:d2791a64-4f43-11f1-9670-5254007c5efd}")
    private String finishedGoodsWarehouseKey;
    @Value("${crm.onec.materials-warehouse-key:ca2e388a-4f43-11f1-9670-5254007c5efd}")
    private String materialsWarehouseKey;
    @Value("${crm.onec.finished-goods-account-bu-key:7b94471e-e49a-11f0-8ef7-5254007c5efd}")
    private String finishedGoodsAccountBuKey;
    @Value("${crm.onec.finished-goods-account-nu-key:803c5e0a-e49a-11f0-8ef7-5254007c5efd}")
    private String finishedGoodsAccountNuKey;
    @Value("${crm.onec.materials-account-bu-key:7b9446ec-e49a-11f0-8ef7-5254007c5efd}")
    private String materialsAccountBuKey;
    @Value("${crm.onec.materials-account-nu-key:803c5dc4-e49a-11f0-8ef7-5254007c5efd}")
    private String materialsAccountNuKey;
    @Value("${crm.onec.write-off-expense-account-bu-key:7b947e46-e49a-11f0-8ef7-5254007c5efd}")
    private String writeOffExpenseAccountBuKey;
    @Value("${crm.onec.write-off-expense-account-nu-key:803c7408-e49a-11f0-8ef7-5254007c5efd}")
    private String writeOffExpenseAccountNuKey;
    @Value("${crm.onec.write-off-vat-receipt-kind-key:8dc33ef4-e49a-11f0-8ef7-5254007c5efd}")
    private String writeOffVatReceiptKindKey;
    @Value("${crm.onec.receipt-income-account-bu-key:7b947978-e49a-11f0-8ef7-5254007c5efd}")
    private String receiptIncomeAccountBuKey;
    @Value("${crm.onec.receipt-income-account-nu-key:803c7214-e49a-11f0-8ef7-5254007c5efd}")
    private String receiptIncomeAccountNuKey;
    @Value("${crm.onec.default-nomenclature-group-key:}")
    private String defaultNomenclatureGroupKey;

    /** Номенклатура строки, подобранная для 1С. */
    private record Line(StockMovement movement, String key, String unitKey, String groupKey, boolean materials) {
    }

    @Transactional
    public void reconcile(Long documentId) {
        StockDocument doc = documentRepository.findById(documentId).orElse(null);
        if (doc == null || !oneCClient.isConfigured()) return;
        try {
            sync(doc);
        } catch (Exception e) {
            boolean unavailable = OneCClient.isUnavailable(e.getMessage()) || e instanceof OneCClient.UnavailableException;
            log.warn("Документ склада №{} не выгружен в 1С: {}", doc.getId(), e.getMessage());
            doc.setOneCSyncError(e.getMessage());
            if (unavailable) {
                doc.setOneCPending(true); // очередь повторит при следующем опросе
            } else {
                notificationService.notifyRole(Role.ADMIN, title(doc) + " не выгружено в 1С: " + e.getMessage(), null);
            }
            documentRepository.save(doc);
        }
    }

    private void sync(StockDocument doc) {
        String entitySet = doc.getType() == StockMovementType.WRITE_OFF ? WRITE_OFF : RECEIPT;
        List<StockMovement> movements = doc.getMovements();
        String ref = doc.getOneCDocumentKey();

        if (movements.isEmpty()) {
            if (ref != null) {
                if (oneCClient.fetchPosted(entitySet, ref)) {
                    oneCClient.unpostDocument(entitySet, ref);
                }
                oneCClient.markForDeletion(entitySet, ref, true);
                log.info("{} — все строки удалены, документ №{} в 1С помечен на удаление", title(doc), doc.getOneCDocumentNumber());
            }
            documentRepository.delete(doc);
            return;
        }

        Map<String, Object> body = body(doc);
        if (ref == null) {
            OneCClient.CreatedDocument created = oneCClient.createDocument(entitySet, body);
            if (created.ref().isBlank()) {
                throw new IllegalStateException("1С не вернула ссылку на созданный документ");
            }
            doc.setOneCDocumentKey(created.ref());
            doc.setOneCDocumentNumber(created.number());
            documentRepository.save(doc); // ссылку запоминаем сразу — повтор не создаст дубль
            ref = created.ref();
        } else {
            if (oneCClient.fetchPosted(entitySet, ref)) {
                oneCClient.unpostDocument(entitySet, ref);
            }
            body.remove("Posted");
            oneCClient.updateDocument(entitySet, ref, body);
        }
        oneCClient.postDocument(entitySet, ref);

        LocalDateTime now = LocalDateTime.now();
        for (StockMovement m : movements) {
            m.setOneCSyncedAt(now);
            m.setOneCDocumentNumber(doc.getOneCDocumentNumber());
        }
        movementRepository.saveAll(movements);
        doc.setOneCSyncError(null);
        documentRepository.save(doc);
        log.info("{} проведено в 1С документом №{} ({} поз.)", title(doc), doc.getOneCDocumentNumber(), movements.size());
    }

    private Map<String, Object> body(StockDocument doc) {
        boolean writeOff = doc.getType() == StockMovementType.WRITE_OFF;
        List<Line> lines = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        for (StockMovement m : doc.getMovements()) {
            String key = m.getProduct() != null ? productKey(m.getProduct()) : ingredientKey(m.getIngredient());
            if (key == null) {
                unresolved.add(m.getProduct() != null ? m.getProduct().getName() : m.getIngredient().getName());
                continue;
            }
            String unit = oneCClient.fetchNomenclatureUnit(key);
            String group = oneCClient.fetchNomenclatureGroup(key);
            if (group == null) group = defaultNomenclatureGroupKey.isBlank() ? ZERO_GUID : defaultNomenclatureGroupKey;
            // Полуфабрикат хранится и учитывается как сырьё (см. OneCProductionSyncService).
            boolean materials = m.getProduct() == null || m.getProduct().isSemiFinished();
            lines.add(new Line(m, key, unit == null ? ZERO_GUID : unit, group, materials));
        }
        if (!unresolved.isEmpty()) {
            throw new IllegalStateException("Не найдено в 1С по названию: " + String.join(", ", unresolved));
        }
        boolean materialsWarehouse = lines.get(0).materials();
        if (lines.stream().anyMatch(l -> l.materials() != materialsWarehouse)) {
            throw new IllegalStateException("В одном документе и полуфабрикаты, и готовая продукция — они на разных складах 1С");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("Date", ONEC_DATE.format(documentTime(doc)));
        body.put("Posted", false);
        body.put("Организация_Key", organizationKey);
        body.put("Склад_Key", materialsWarehouse ? materialsWarehouseKey : finishedGoodsWarehouseKey);
        body.put("ВидУчетаНУ_Key", taxAccountingTypeKey);
        body.put("Комментарий", comment(doc));
        if (!writeOff) {
            body.put("ТипЦен_Key", ZERO_GUID);
        }

        IngredientPriceService.Calculator prices = writeOff ? null : ingredientPriceService.calculator(doc.getDocumentDate());
        List<Map<String, Object>> rows = new ArrayList<>();
        int n = 1;
        BigDecimal total = BigDecimal.ZERO;
        for (Line l : lines) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("LineNumber", String.valueOf(n));
            row.put("ЕдиницаИзмерения_Key", l.unitKey());
            row.put("Количество", l.movement().getQuantity());
            row.put("Коэффициент", 1);
            row.put("Номенклатура_Key", l.key());
            row.put("СчетУчетаБУ_Key", l.materials() ? materialsAccountBuKey : finishedGoodsAccountBuKey);
            row.put("СчетУчетаНУ_Key", l.materials() ? materialsAccountNuKey : finishedGoodsAccountNuKey);
            if (writeOff) {
                row.put("СчетЗатратБУ_Key", writeOffExpenseAccountBuKey);
                row.put("СубконтоЗатратБУ1", ZERO_GUID);
                row.put("СубконтоЗатратБУ1_Type", "StandardODATA.Catalog_СтатьиЗатрат");
                row.put("СубконтоЗатратБУ2", l.groupKey());
                row.put("СубконтоЗатратБУ2_Type", "StandardODATA.Catalog_НоменклатурныеГруппы");
                row.put("СубконтоЗатратБУ3", ZERO_GUID);
                row.put("СубконтоЗатратБУ3_Type", "StandardODATA.Catalog_ПодразделенияОрганизаций");
                row.put("СчетЗатратНУ_Key", writeOffExpenseAccountNuKey);
                row.put("СубконтоЗатратНУ1", ZERO_GUID);
                row.put("СубконтоЗатратНУ1_Type", "StandardODATA.Catalog_СтатьиЗатрат");
                row.put("СубконтоЗатратНУ2", l.groupKey());
                row.put("СубконтоЗатратНУ2_Type", "StandardODATA.Catalog_НоменклатурныеГруппы");
                row.put("НДСВидОборота", "Общий");
                row.put("НДСВидПоступления_Key", writeOffVatReceiptKindKey);
                row.put("КлючСвязи", String.valueOf(n));
                row.put("ПричинаСписания", doc.getReason() == null ? "" : truncate(doc.getReason(), 100));
            } else {
                BigDecimal price = BigDecimal.ZERO;
                if (l.movement().getIngredient() != null) {
                    price = prices.price(l.movement().getIngredient())
                            .map(IngredientPriceService.Price::unitPrice).orElse(BigDecimal.ZERO)
                            .setScale(2, RoundingMode.HALF_UP);
                }
                BigDecimal sum = price.multiply(l.movement().getQuantity()).setScale(2, RoundingMode.HALF_UP);
                total = total.add(sum);
                row.put("Цена", price);
                row.put("Сумма", sum);
                row.put("КорСчетОприходованияБУ_Key", receiptIncomeAccountBuKey);
                row.put("КорСубконтоОприходованияБУ1", ZERO_GUID);
                row.put("КорСубконтоОприходованияБУ1_Type", "StandardODATA.Catalog_Доходы");
                row.put("КорСубконтоОприходованияБУ2", l.groupKey());
                row.put("КорСубконтоОприходованияБУ2_Type", "StandardODATA.Catalog_НоменклатурныеГруппы");
                row.put("КорСубконтоОприходованияБУ3", ZERO_GUID);
                row.put("КорСубконтоОприходованияБУ3_Type", "StandardODATA.Catalog_ПодразделенияОрганизаций");
                row.put("КорСчетОприходованияНУ_Key", receiptIncomeAccountNuKey);
                row.put("КорСубконтоОприходованияНУ1", ZERO_GUID);
                row.put("КорСубконтоОприходованияНУ1_Type", "StandardODATA.Catalog_Доходы");
                row.put("КорСубконтоОприходованияНУ2", l.key());
                row.put("КорСубконтоОприходованияНУ2_Type", "StandardODATA.Catalog_Номенклатура");
                row.put("НомерГТД_Key", ZERO_GUID);
            }
            rows.add(row);
            n++;
        }
        if (!writeOff) {
            body.put("СуммаДокумента", total);
        }
        body.put("Товары", rows);
        return body;
    }

    /** Сегодняшний документ — временем создания, прошлой датой — серединой дня. */
    private static LocalDateTime documentTime(StockDocument doc) {
        LocalDate date = doc.getDocumentDate();
        return date.equals(doc.getCreatedAt().toLocalDate())
                ? doc.getCreatedAt().withNano(0)
                : date.atTime(LocalTime.NOON);
    }

    private static String comment(StockDocument doc) {
        String base = (doc.getType() == StockMovementType.WRITE_OFF ? "Списание" : "Поступление")
                + " CRM №" + doc.getId() + " (" + doc.getCreatedBy() + ")";
        return doc.getReason() == null ? base : base + ": " + doc.getReason();
    }

    private static String title(StockDocument doc) {
        return (doc.getType() == StockMovementType.WRITE_OFF ? "Списание" : "Поступление")
                + (doc.isProduct() ? " товара" : " сырья") + " №" + doc.getId();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private String productKey(Product product) {
        if (product.getOneCNomenclatureKey() != null) return product.getOneCNomenclatureKey();
        String key = findNomenclatureKeyByName(product.getName());
        if (key == null) return null;
        product.setOneCNomenclatureKey(key);
        productRepository.save(product);
        return key;
    }

    private String ingredientKey(Ingredient ingredient) {
        if (ingredient.getOneCNomenclatureKey() != null) return ingredient.getOneCNomenclatureKey();
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
}
