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
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * При оформлении закупки в CRM (см. PurchaseService.create) создаёт в 1С документ "Поступление
 * товаров и услуг" с фиксированным временем документа 05:00 — раньше времени остальных
 * документов дня (Покупка приходит на склад первой, до начала смены). Все позиции закупки (см.
 * PurchaseItem) — одним документом, вид операции и аналитика шапки/строк берутся с ЛЮБОГО
 * существующего в базе документа этого типа (см.
 * OneCClient.findAnyGoodsReceiptTemplate/findAnyGoodsReceiptLineTemplate) — тот же приём, что и
 * у "Реализации"/"Оприходования".
 *
 * <p>Документ создаётся НЕпроведённым и проводится отдельным PATCH сразу после (см. {@link
 * OneCClient#postDocument}) — раньше Posted:true стоял прямо в теле создания, но так документ
 * молча оставался непроведённым (проверено 2026-09-22 тестовой закупкой №163: 1С вернула 200
 * OK и не сообщила об ошибке, но сам документ оказался с Posted:false, без движений по
 * складу — тот же класс проблемы, что уже нашли на "Реализации", см. коммит a575e67). Отдельный
 * PATCH — тот же приём, что уже надёжно работает у "Требования-накладной".
 *
 * <p>Best-effort — закупка в CRM уже сохранена и не откатывается, если 1С недоступна, что-то не
 * сопоставилось или проведение не удалось; причина пишется в {@link Purchase#getOneCSyncError()}
 * и уходит уведомлением админу. Ссылка на созданный документ (даже непроведённый) сохраняется в
 * любом случае — см. {@link Purchase#getOneCDocumentKey()}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OneCPurchaseSyncService {

    private static final DateTimeFormatter ONEC_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final LocalTime PURCHASE_TIME = LocalTime.of(5, 0);

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
            onSyncFailure(purchase, e);
        }
    }

    /**
     * То же самое, что {@link #trySyncPurchase}, но для ручной повторной попытки (см.
     * PurchaseService.resyncOneC) — бросает исключение вместо тихого логирования, если 1С не
     * настроена или синхронизация не удалась, чтобы пользователь увидел причину.
     */
    @Transactional
    public void syncOrThrow(Purchase purchase) {
        if (!oneCClient.isConfigured()) {
            throw new IllegalStateException("Подключение к 1С не настроено (crm.onec.base-url/username/password)");
        }
        try {
            sync(purchase);
        } catch (Exception e) {
            onSyncFailure(purchase, e);
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private void onSyncFailure(Purchase purchase, Exception e) {
        log.warn("Не удалось выгрузить закупку №{} в 1С: {}", purchase.getId(), e.getMessage());
        purchase.setOneCSyncError(e.getMessage());
        purchaseRepository.save(purchase);
        notificationService.notifyRole(Role.ADMIN,
                "Закупка №" + purchase.getId() + " не выгружена в 1С: " + e.getMessage(), null);
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
        body.put("Date", ONEC_DATE.format(LocalDateTime.of(purchase.getPurchaseDate(), PURCHASE_TIME)));
        body.put("Posted", false);
        // Не берём из шаблона (см. OneCClient.findAnyGoodsReceiptTemplate) — "любой" документ
        // в базе может быть другого вида операции (обнаружено на закупке №13: шаблоном
        // случайно оказался документ "ОсновныеСредства", из-за чего товары не попадали в
        // табличную часть и документ уходил не в тот раздел 1С).
        body.put("ВидОперации", "Товары");
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

        // Отдельным PATCH после создания, а не Posted:true сразу в теле создания — на
        // "Реализации товаров и услуг" такой одношаговый способ оказался ненадёжен: 1С отвечала
        // 200 OK, но реально документ оставался непроведённым, без движений по складу (см.
        // OneCOrderSyncService, класс-javadoc и коммит a575e67). Отдельный PATCH — тот же приём,
        // что уже надёжно работает у "Требования-накладной" (см. {@link
        // OneCProductionSyncService#postRequisition}).
        oneCClient.postDocument("Document_ПоступлениеТоваровУслуг", created.ref());
        log.info("Закупка №{} выгружена в 1С проведённым документом №{}", purchase.getId(), created.number());
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
