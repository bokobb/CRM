package com.confectionery.crm.service;

import com.confectionery.crm.domain.Client;
import com.confectionery.crm.domain.Product;
import com.confectionery.crm.domain.ProductReturn;
import com.confectionery.crm.domain.ProductReturnItem;
import com.confectionery.crm.domain.Role;
import com.confectionery.crm.repository.ProductRepository;
import com.confectionery.crm.repository.ProductReturnRepository;
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
 * При создании возврата в CRM (см. ProductReturnService.create) создаёт в 1С документ "Возврат
 * товаров от покупателя" на склад "Испорченные товары" и сразу проводит его — ровно так, как
 * бухгалтер до этого заводил такие возвраты вручную (вид операции "ВозвратТоваров", без
 * документа-основания). Время документа — 18:00: после отгрузок того же дня (у "Реализации" 07:00).
 *
 * <p>Шапка и строки — по образцу уже проведённого возврата из базы, строго по белому списку
 * (см. OneCClient.findAnyCustomerReturnTemplate); номенклатурная группа и номенклатура товара
 * подставляются поверх, как у "Реализации" (см. OneCOrderSyncService.buildSyntheticTemplate).
 *
 * <p>При правке возврата тот же документ распроводится, перезаписывается и проводится заново
 * (см. {@link #sync}); при удалении — распроводится и помечается на удаление (см. {@link #deleteOrThrow}).
 *
 * <p>Та же схема отказоустойчивости, что у закупок (см. OneCPurchaseSyncService): возврат в CRM
 * уже сохранён и не откатывается; ссылка на созданный документ сохраняется до проведения, чтобы
 * повторная попытка не плодила дубли, а перезаписала и провела уже существующий документ.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OneCReturnSyncService {

    private static final String ENTITY_SET = "Document_ВозвратТоваровОтПокупателя";
    private static final DateTimeFormatter ONEC_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final LocalTime RETURN_TIME = LocalTime.of(18, 0);

    private final OneCClient oneCClient;
    private final ProductReturnRepository productReturnRepository;
    private final ProductRepository productRepository;
    private final NotificationService notificationService;
    private final OneCClientPriceSyncService oneCClientPriceSyncService;

    @Value("${crm.onec.organization-key:}")
    private String organizationKey;
    @Value("${crm.onec.spoiled-goods-warehouse-key:e3990e56-8fcd-11f1-97cf-5254007c5efd}")
    private String spoiledGoodsWarehouseKey;
    @Value("${crm.onec.default-nomenclature-group-key:}")
    private String defaultNomenclatureGroupKey;

    /** Фоновая очередь (OneCQueue): привести документ 1С к состоянию возврата в CRM. */
    @Transactional
    public void reconcile(Long returnId) {
        productReturnRepository.findById(returnId).ifPresent(this::trySync);
    }

    /** Никогда не бросает исключение наружу — вызывается и из фоновой очереди. */
    @Transactional
    public void trySync(ProductReturn productReturn) {
        if (!oneCClient.isConfigured()) {
            return;
        }
        try {
            sync(productReturn);
        } catch (Exception e) {
            onSyncFailure(productReturn, e);
        }
    }

    /** Ручная повторная попытка (см. ProductReturnService.resyncOneC) — бросает исключение, чтобы показать причину. */
    @Transactional
    public void syncOrThrow(ProductReturn productReturn) {
        if (!oneCClient.isConfigured()) {
            throw new IllegalStateException("Подключение к 1С не настроено (crm.onec.base-url/username/password)");
        }
        try {
            sync(productReturn);
        } catch (Exception e) {
            onSyncFailure(productReturn, e);
            throw new IllegalStateException(e.getMessage(), e);
        }
        // Неудачное проведение сюда не бросается намеренно (см. post): откат транзакции потерял
        // бы уже сохранённый oneCDocumentKey — причина вернётся в oneCSyncError ответа.
    }

    private void onSyncFailure(ProductReturn productReturn, Exception e) {
        log.warn("Не удалось выгрузить возврат №{} в 1С: {}", productReturn.getId(), e.getMessage());
        productReturn.setOneCSyncError(e.getMessage());
        productReturnRepository.save(productReturn);
        notifyAdmin(productReturn, "не выгружен в 1С: " + e.getMessage());
    }

    private void sync(ProductReturn productReturn) {
        Map<String, Object> body = buildBody(productReturn);
        if (productReturn.getOneCDocumentKey() == null) {
            OneCClient.CreatedDocument created = oneCClient.createCustomerReturn(body);
            productReturn.setOneCDocumentKey(created.ref());
            productReturn.setOneCDocumentNumber(created.number());
            productReturn.setOneCSyncError(null);
            productReturnRepository.save(productReturn);
            log.info("Возврат №{} выгружен в 1С документом №{}", productReturn.getId(), created.number());
            post(productReturn);
            return;
        }
        // Документ уже есть (правка возврата или повтор после неудачи): проведённый документ 1С
        // через OData перезаписывать нельзя — сначала отменяем проведение, потом перезаписываем
        // шапку и "Товары" целиком и проводим заново.
        unpostIfPosted(productReturn.getOneCDocumentKey());
        oneCClient.updateDocument(ENTITY_SET, productReturn.getOneCDocumentKey(), body);
        log.info("Возврат №{} — документ №{} перезаписан в 1С", productReturn.getId(), productReturn.getOneCDocumentNumber());
        post(productReturn);
    }

    /**
     * Удаление возврата (см. ProductReturnService.delete): отменяет проведение и ставит пометку
     * удаления — физически удаляет помеченное бухгалтер в 1С сам. Бросает исключение, если 1С
     * не ответила как надо: тогда и в CRM возврат не удаляется, чтобы учёт не разошёлся.
     */
    public void deleteOrThrow(ProductReturn productReturn) {
        if (productReturn.getOneCDocumentKey() == null) {
            return;
        }
        if (!oneCClient.isConfigured()) {
            throw new IllegalStateException("Возврат выгружен в 1С, а подключение к 1С не настроено — удалить нельзя");
        }
        try {
            unpostIfPosted(productReturn.getOneCDocumentKey());
            oneCClient.markForDeletion(ENTITY_SET, productReturn.getOneCDocumentKey(), true);
            log.info("Возврат №{} — документ №{} в 1С распроведён и помечен на удаление",
                    productReturn.getId(), productReturn.getOneCDocumentNumber());
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось отменить документ №" + productReturn.getOneCDocumentNumber()
                    + " в 1С: " + e.getMessage(), e);
        }
    }

    private void unpostIfPosted(String ref) {
        if (!oneCClient.fetchPosted(ENTITY_SET, ref)) {
            return;
        }
        oneCClient.unpostDocument(ENTITY_SET, ref);
        if (oneCClient.fetchPosted(ENTITY_SET, ref)) {
            throw new IllegalStateException("1С не отменила проведение документа — отмените его в 1С вручную");
        }
    }

    private Map<String, Object> buildBody(ProductReturn productReturn) {
        Client client = productReturn.getClient();
        if (client.getOneCContragentKey() == null || client.getOneCContractKey() == null) {
            throw new IllegalStateException("Клиент «" + client.getName() + "» не привязан к 1С — свяжите его в карточке клиента");
        }

        Map<String, Object> headerTemplate = oneCClient.findAnyCustomerReturnTemplate();
        Map<String, Object> lineTemplate = oneCClient.findAnyCustomerReturnLineTemplate();
        if (headerTemplate == null || lineTemplate == null) {
            throw new IllegalStateException("В 1С нет ни одного проведённого документа \"Возврат товаров от покупателя\" — не с чего скопировать шаблон");
        }

        List<Map<String, Object>> lines = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        int lineNumber = 1;
        for (ProductReturnItem item : productReturn.getItems()) {
            Product product = item.getProduct();
            String nomenclatureKey = resolveNomenclatureKey(product);
            if (nomenclatureKey == null) {
                unresolved.add(product.getName());
                continue;
            }
            String groupKey = oneCClient.fetchNomenclatureGroup(nomenclatureKey);
            if (groupKey == null) groupKey = defaultNomenclatureGroupKey;

            Map<String, Object> line = new LinkedHashMap<>(lineTemplate);
            line.put("LineNumber", String.valueOf(lineNumber));
            line.put("КлючСвязи", String.valueOf(lineNumber));
            lineNumber++;
            line.put("Номенклатура_Key", nomenclatureKey);
            line.put("Количество", item.getQuantity());
            line.put("Цена", item.getPrice());
            line.put("Сумма", item.getPrice().multiply(item.getQuantity()));
            line.put("Себестоимость", BigDecimal.ZERO);
            line.put("СуммаНДС", BigDecimal.ZERO);
            line.put("СуммаАкциза", BigDecimal.ZERO);
            line.put("СубконтоДоходовБУ2", groupKey);
            line.put("СубконтоСписанияСебестоимостиБУ2", groupKey);
            line.put("СубконтоСписанияСебестоимостиНУ2", groupKey);
            line.put("СубконтоДоходовНУ2", nomenclatureKey);
            lines.add(line);
        }
        if (!unresolved.isEmpty()) {
            throw new IllegalStateException("Не найдено в 1С по названию: " + String.join(", ", unresolved));
        }

        Map<String, Object> body = new LinkedHashMap<>(headerTemplate);
        body.put("Date", ONEC_DATE.format(LocalDateTime.of(productReturn.getReturnDate(), RETURN_TIME)));
        body.put("Posted", false);
        body.put("ВидОперации", "ВозвратТоваров");
        body.put("Организация_Key", organizationKey);
        body.put("Контрагент_Key", client.getOneCContragentKey());
        body.put("ДоговорКонтрагента_Key", client.getOneCContractKey());
        body.put("Склад_Key", spoiledGoodsWarehouseKey);
        String priceTypeKey = oneCClientPriceSyncService.resolvePriceTypeKey(client);
        if (priceTypeKey != null) {
            body.put("ТипЦен_Key", priceTypeKey);
        }
        String comment = "Возврат CRM №" + productReturn.getId();
        if (productReturn.getReason() != null && !productReturn.getReason().isBlank()) {
            comment += " (" + productReturn.getReason() + ")";
        }
        body.put("Комментарий", comment);
        body.put("Товары", lines);
        return body;
    }

    private void post(ProductReturn productReturn) {
        try {
            oneCClient.postDocument(ENTITY_SET, productReturn.getOneCDocumentKey());
            productReturn.setOneCSyncError(null);
            productReturnRepository.save(productReturn);
            log.info("Возврат №{} — документ №{} проведён в 1С", productReturn.getId(), productReturn.getOneCDocumentNumber());
        } catch (Exception e) {
            String message = "документ №" + productReturn.getOneCDocumentNumber()
                    + " создан, но не проведён в 1С: " + e.getMessage();
            log.warn("Возврат №{} — {}", productReturn.getId(), message);
            productReturn.setOneCSyncError(message);
            productReturnRepository.save(productReturn);
            notifyAdmin(productReturn, message);
        }
    }

    private String resolveNomenclatureKey(Product product) {
        if (product.getOneCNomenclatureKey() != null) {
            return product.getOneCNomenclatureKey();
        }
        String needle = product.getName().trim().toLowerCase();
        String key = oneCClient.fetchNomenclatureNames().entrySet().stream()
                .filter(e -> e.getValue().trim().toLowerCase().equals(needle))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
        if (key != null) {
            product.setOneCNomenclatureKey(key);
            productRepository.save(product);
        }
        return key;
    }

    private void notifyAdmin(ProductReturn productReturn, String message) {
        notificationService.notifyRole(Role.ADMIN, "Возврат №" + productReturn.getId() + " " + message, null);
    }
}
