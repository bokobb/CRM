package com.confectionery.crm.service;

import com.confectionery.crm.domain.Counterparty;
import com.confectionery.crm.domain.Ingredient;
import com.confectionery.crm.domain.Purchase;
import com.confectionery.crm.domain.PurchaseItem;
import com.confectionery.crm.domain.Role;
import com.confectionery.crm.domain.StockMovement;
import com.confectionery.crm.repository.CounterpartyRepository;
import com.confectionery.crm.repository.IngredientRepository;
import com.confectionery.crm.repository.PurchaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Обратный импорт: зав. складом/бухгалтер иногда заводит "Поступление товаров и услуг" ПРЯМО в
 * 1С, минуя CRM (например, закупку у поставщика оформили сразу в 1С) — тогда CRM о ней не знает.
 * Этот сервис читает такие документы из 1С и заводит по ним обычные Закупки в CRM — симметрично
 * {@link OneCOrderImportService}, см. его javadoc за подробностями дедупликации/сопоставления.
 *
 * <p>В отличие от заказа, у {@link PurchaseItem} обязательна ссылка на {@link StockMovement} (см.
 * PurchaseItem.stockMovement) — поэтому импорт КАЖДОЙ позиции реально проводит приход по
 * {@link StockService#receive}, как обычная ручная закупка. Задвоения с периодической
 * синхронизацией остатков (см. OneCStockSyncService, читает абсолютный остаток из 1С каждые 5
 * минут) не будет — она всё равно перезатирает остаток абсолютным значением из 1С, а не
 * складывает.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OneCPurchaseImportService {

    private static final DateTimeFormatter ONEC_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final String CRM_MARKER = "Закупка CRM №";
    private static final String IMPORTED_BY = "1С (импорт)";

    private final OneCClient oneCClient;
    private final PurchaseRepository purchaseRepository;
    private final CounterpartyRepository counterpartyRepository;
    private final IngredientRepository ingredientRepository;
    private final StockService stockService;
    private final NotificationService notificationService;

    /** Каждые 5 минут в рабочее время (7:00–18:55) — симметрично {@link OneCOrderImportService#scheduledImport()}. */
    @Scheduled(cron = "${crm.onec.document-import-cron:0 */5 7-18 * * *}")
    public void scheduledImport() {
        if (!oneCClient.isConfigured()) {
            return;
        }
        try {
            ImportResult result = importAll();
            if (result.imported() > 0 || !result.unmatched().isEmpty()) {
                log.info("Импорт закупок из 1С: создано {}, не сопоставлено {}", result.imported(), result.unmatched().size());
            }
            if (result.imported() > 0) {
                notificationService.notifyRole(Role.ADMIN,
                        "Импорт из 1С: создано закупок из документов \"Поступление\" — " + result.imported(), null);
            }
            if (!result.unmatched().isEmpty()) {
                notificationService.notifyRole(Role.ADMIN,
                        "Импорт из 1С: не удалось сопоставить " + result.unmatched().size()
                                + " документ(ов) \"Поступление\" — " + String.join("; ", result.unmatched()), null);
            }
        } catch (Exception e) {
            log.warn("Импорт закупок из 1С не удался: {}", e.getMessage());
        }
    }

    @Transactional
    public ImportResult importAll() {
        List<OneCClient.GoodsReceiptHeader> headers = oneCClient.fetchGoodsReceiptHeaders();
        List<OneCClient.GoodsReceiptLine> lines = oneCClient.fetchGoodsReceiptLines();
        Map<String, List<OneCClient.GoodsReceiptLine>> linesByParent = new HashMap<>();
        for (OneCClient.GoodsReceiptLine line : lines) {
            linesByParent.computeIfAbsent(line.parentRef(), k -> new ArrayList<>()).add(line);
        }
        Map<String, String> nomenclatureNames = oneCClient.fetchNomenclatureNames();
        Map<String, Ingredient> ingredientByName = new HashMap<>();
        for (Ingredient i : ingredientRepository.findAll()) {
            ingredientByName.putIfAbsent(i.getName().trim().toLowerCase(), i);
        }

        int imported = 0;
        List<String> unmatched = new ArrayList<>();
        for (OneCClient.GoodsReceiptHeader header : headers) {
            if (header.comment() != null && header.comment().startsWith(CRM_MARKER)) {
                continue;
            }
            if (purchaseRepository.findByOneCDocumentKey(header.ref()).isPresent()) {
                continue;
            }
            Counterparty counterparty = counterpartyRepository.findByOneCContragentKey(header.counterpartyKey()).orElse(null);
            if (counterparty == null) {
                unmatched.add("№" + header.number() + ": контрагент не привязан к CRM");
                continue;
            }
            List<OneCClient.GoodsReceiptLine> docLines = linesByParent.getOrDefault(header.ref(), List.of());
            if (docLines.isEmpty()) {
                continue;
            }
            // Сначала проверяем, что сопоставится ВСЁ — приход по StockService необратим без
            // отдельного списания, поэтому частично собранный документ здесь не оставляем.
            List<Ingredient> resolvedIngredients = new ArrayList<>();
            String unmatchedLine = null;
            for (OneCClient.GoodsReceiptLine line : docLines) {
                Ingredient ingredient = resolveIngredient(line.nomenclatureKey(), nomenclatureNames, ingredientByName);
                if (ingredient == null) {
                    unmatchedLine = nomenclatureNames.getOrDefault(line.nomenclatureKey(), line.nomenclatureKey());
                    break;
                }
                resolvedIngredients.add(ingredient);
            }
            if (unmatchedLine != null) {
                unmatched.add("№" + header.number() + ": сырьё «" + unmatchedLine + "» не найдено в CRM");
                continue;
            }

            LocalDate purchaseDate = parseDate(header.date()).toLocalDate();
            String reason = "Импорт из 1С, документ №" + header.number();
            Purchase purchase = new Purchase();
            purchase.setCounterparty(counterparty);
            purchase.setPurchaseDate(purchaseDate);
            purchase.setCreatedBy(IMPORTED_BY);
            purchase.setOneCDocumentKey(header.ref());
            purchase.setOneCDocumentNumber(header.number());
            for (int i = 0; i < docLines.size(); i++) {
                OneCClient.GoodsReceiptLine line = docLines.get(i);
                Ingredient ingredient = resolvedIngredients.get(i);
                StockMovement movement = stockService.receive(ingredient, line.quantity(), reason, IMPORTED_BY);
                PurchaseItem item = new PurchaseItem();
                item.setIngredient(ingredient);
                item.setQuantity(line.quantity());
                item.setTotalPrice(line.price() == null ? null : line.price().multiply(line.quantity()));
                item.setStockMovement(movement);
                purchase.addItem(item);
            }
            purchaseRepository.save(purchase);
            imported++;
        }
        return new ImportResult(imported, unmatched);
    }

    private Ingredient resolveIngredient(String nomenclatureKey, Map<String, String> nomenclatureNames, Map<String, Ingredient> ingredientByName) {
        Ingredient byKey = ingredientRepository.findByOneCNomenclatureKey(nomenclatureKey).orElse(null);
        if (byKey != null) {
            return byKey;
        }
        String name = nomenclatureNames.get(nomenclatureKey);
        if (name == null) {
            return null;
        }
        Ingredient byName = ingredientByName.get(name.trim().toLowerCase());
        if (byName != null) {
            byName.setOneCNomenclatureKey(nomenclatureKey);
            ingredientRepository.save(byName);
        }
        return byName;
    }

    private LocalDateTime parseDate(String date) {
        try {
            return LocalDateTime.parse(date, ONEC_DATE);
        } catch (Exception e) {
            return LocalDateTime.now();
        }
    }

    public record ImportResult(int imported, List<String> unmatched) {
    }
}
