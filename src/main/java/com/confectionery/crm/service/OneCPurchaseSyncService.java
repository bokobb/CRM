package com.confectionery.crm.service;

import com.confectionery.crm.domain.Counterparty;
import com.confectionery.crm.domain.Ingredient;
import com.confectionery.crm.domain.Purchase;
import com.confectionery.crm.domain.PurchaseItem;
import com.confectionery.crm.domain.Role;
import com.confectionery.crm.repository.IngredientRepository;
import com.confectionery.crm.repository.PurchaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * При оформлении закупки в CRM (см. PurchaseService.create) создаёт в 1С документ "Поступление
 * товаров и услуг" — НЕпроведённым (Posted:false), бухгалтер проверяет и проводит сам, как и
 * "Реализацию" по заказам (см. OneCOrderSyncService). Все позиции закупки (см. PurchaseItem) —
 * одним документом, вид операции и аналитика шапки/строк берутся с ЛЮБОГО существующего в базе
 * документа этого типа (см. OneCClient.findAnyGoodsReceiptTemplate/findAnyGoodsReceiptLineTemplate) —
 * тот же приём, что и у "Реализации"/"Оприходования".
 *
 * <p><b>Внимание:</b> этот сервис, в отличие от остальной интеграции с 1С в проекте, НЕ
 * проверялся на реальной базе клиента (нет доступа к тестовому стенду 1С из среды разработки) —
 * названия набора {@code Document_ПоступлениеТоваровУслуг}/{@code ..._Товары} и состав полей
 * шапки взяты по стандартной конфигурации 1С:Бухгалтерия/Fresh. Если 1С отклонит документ —
 * причина попадёт в {@link Purchase#getOneCSyncError()} как обычно, нужно будет свериться с
 * реальным документом в 1С и поправить набор полей здесь.
 *
 * <p>Best-effort — закупка в CRM уже сохранена и не откатывается, если 1С недоступна или
 * что-то не сопоставилось; причина пишется в {@link Purchase#getOneCSyncError()} и уходит
 * уведомлением админу.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OneCPurchaseSyncService {

    private static final DateTimeFormatter ONEC_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final OneCClient oneCClient;
    private final PurchaseRepository purchaseRepository;
    private final IngredientRepository ingredientRepository;
    private final NotificationService notificationService;

    @Value("${crm.onec.organization-key:}")
    private String organizationKey;
    @Value("${crm.onec.materials-warehouse-key:ca2e388a-4f43-11f1-9670-5254007c5efd}")
    private String materialsWarehouseKey;

    /** Никогда не бросает исключение наружу — вызывается сразу после сохранения закупки. */
    @Transactional
    public void trySyncPurchase(Purchase purchase) {
        if (!oneCClient.isConfigured()) {
            return;
        }
        try {
            sync(purchase);
        } catch (Exception e) {
            log.warn("Не удалось выгрузить закупку №{} в 1С: {}", purchase.getId(), e.getMessage());
            purchase.setOneCSyncError(e.getMessage());
            purchaseRepository.save(purchase);
            notificationService.notifyRole(Role.ADMIN,
                    "Закупка №" + purchase.getId() + " не выгружена в 1С: " + e.getMessage(), null);
        }
    }

    private void sync(Purchase purchase) {
        Counterparty counterparty = purchase.getCounterparty();
        if (counterparty.getOneCContragentKey() == null) {
            String message = "Контрагент «" + counterparty.getName() + "» не привязан к 1С — загрузите контрагентов из 1С на странице «Контрагенты»";
            purchase.setOneCSyncError(message);
            purchaseRepository.save(purchase);
            notificationService.notifyRole(Role.ADMIN,
                    "Закупка №" + purchase.getId() + " не выгружена в 1С: " + message, null);
            return;
        }

        Map<String, Object> headerTemplate = oneCClient.findAnyGoodsReceiptTemplate();
        Map<String, Object> lineTemplate = oneCClient.findAnyGoodsReceiptLineTemplate();
        if (headerTemplate == null || lineTemplate == null) {
            throw new IllegalStateException("В 1С нет ни одного документа \"Поступление товаров и услуг\" — не с чего скопировать шаблон");
        }

        List<Map<String, Object>> lines = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        int lineNumber = 1;
        for (PurchaseItem item : purchase.getItems()) {
            String key = resolveIngredientKey(item.getIngredient());
            if (key == null) {
                unresolved.add(item.getIngredient().getName());
                continue;
            }
            BigDecimal quantity = item.getQuantity();
            BigDecimal total = item.getTotalPrice() != null ? item.getTotalPrice() : BigDecimal.ZERO;
            BigDecimal price = quantity.signum() == 0 ? BigDecimal.ZERO
                    : total.divide(quantity, 2, java.math.RoundingMode.HALF_UP);

            Map<String, Object> line = new LinkedHashMap<>(lineTemplate);
            line.put("LineNumber", String.valueOf(lineNumber++));
            line.put("Номенклатура_Key", key);
            line.put("Количество", quantity);
            line.put("Цена", price);
            line.put("Сумма", total);
            lines.add(line);
        }
        if (!unresolved.isEmpty()) {
            throw new IllegalStateException("Не найдено в 1С по названию: " + String.join(", ", unresolved));
        }

        Map<String, Object> body = new LinkedHashMap<>(headerTemplate);
        body.put("Date", ONEC_DATE.format(purchase.getPurchaseDate().atTime(12, 0)));
        body.put("Posted", false);
        body.put("Организация_Key", organizationKey);
        body.put("Контрагент_Key", counterparty.getOneCContragentKey());
        body.put("ДоговорКонтрагента_Key", counterparty.getOneCContractKey());
        body.put("Склад_Key", materialsWarehouseKey);
        body.put("Комментарий", "Закупка CRM №" + purchase.getId());
        body.put("Товары", lines);

        OneCClient.CreatedDocument created = oneCClient.createGoodsReceiptDocument(body);
        purchase.setOneCDocumentKey(created.ref());
        purchase.setOneCDocumentNumber(created.number());
        purchase.setOneCSyncError(null);
        purchaseRepository.save(purchase);
        log.info("Закупка №{} выгружена в 1С непроведённым документом №{}", purchase.getId(), created.number());
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
}
