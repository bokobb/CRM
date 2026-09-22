package com.confectionery.crm.service;

import com.confectionery.crm.domain.Client;
import com.confectionery.crm.domain.Order;
import com.confectionery.crm.domain.OrderItem;
import com.confectionery.crm.domain.Product;
import com.confectionery.crm.domain.Role;
import com.confectionery.crm.repository.OrderRepository;
import com.confectionery.crm.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * При оформлении заказа в CRM создаёт в 1С соответствующий документ "Реализация товаров и
 * услуг" — НЕпроведённым (Posted:false), чтобы бухгалтер проверил его и провёл вручную. В
 * этой базе 1С нет отдельного документа "Заказ покупателя" — только "Реализация" (см.
 * OneCClient), поэтому именно она играет роль черновика заказа для 1С.
 *
 * <p>При изменении состава уже выгруженного заказа (см. OrderService.updateItems) тот же метод
 * {@link #trySyncOrder} перезаписывает "Товары" в уже созданном документе (PATCH), а не создаёт
 * второй — но только если документ ещё не проведён бухгалтером в 1С; если уже проведён, менять
 * его состав через OData не проведя заново — значит молча разойтись с фактическими проводками,
 * поэтому в этом случае синхронизация останавливается с понятной ошибкой и правки в проведённый
 * документ бухгалтер вносит в 1С вручную.
 *
 * <p>При отмене заказа (см. OrderService.cancel) {@link #trySyncCancel} ставит в документе флаг
 * "Пометка удаления" — не проводит и не изменяет его иначе, фактическое удаление в 1С (со всеми
 * её проверками на зависимые движения) бухгалтер выполняет сам.
 *
 * <p>Дата документа ({@code Date}) — пока заказ не отгружен, дата его создания в CRM (черновик
 * ещё может переезжать датой вместе с правками состава); как только нажата кнопка "Отгружен"
 * (см. OrderService.ship), дата меняется на фактическую дату отгрузки — именно она отражает,
 * когда товар реально ушёл со склада. Время документа ВСЕГДА фиксированное 07:00 — так принято
 * в этой базе 1С. Тогда же документ и проводится (см. {@link #postIfShipped}) — это и есть
 * фактическое списание товара со склада в 1С.
 *
 * <p>Раньше автопроведение отсюда было убрано: {@code PATCH Posted:true} выставлял флаг, но
 * движений по складу не формировал. Причина оказалась не в этом документе — запись реквизита
 * вообще не запускает проведение, нужна OData-операция {@code /Post} (см.
 * {@link OneCClient#postDocument}). С ней проведение работает.
 *
 * <p>Best-effort, как и остальные интеграции (см. WhatsAppGatewayClient.sendInvoiceToClient):
 * заказ в CRM уже сохранён и не откатывается, если 1С недоступна или что-то не сопоставилось —
 * причина пишется в {@link Order#getOneCSyncError()} и уходит уведомлением админу.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OneCOrderSyncService {

    private static final DateTimeFormatter ONEC_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final OneCClient oneCClient;
    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;
    private final NotificationService notificationService;
    private final OneCClientPriceSyncService oneCClientPriceSyncService;

    @Value("${crm.onec.organization-key:}")
    private String organizationKey;
    @Value("${crm.onec.currency-key:}")
    private String currencyKey;
    @Value("${crm.onec.tax-accounting-type-key:}")
    private String taxAccountingTypeKey;
    @Value("${crm.onec.advance-account-key:}")
    private String advanceAccountKey;
    @Value("${crm.onec.settlement-account-key:}")
    private String settlementAccountKey;
    @Value("${crm.onec.finished-goods-warehouse-key:d2791a64-4f43-11f1-9670-5254007c5efd}")
    private String warehouseKey;

    /**
     * Никогда не бросает исключение наружу — вызывается и сразу после создания заказа, и после
     * любого изменения его состава (см. OrderService.createOrder/updateItems). Сам решает,
     * создавать документ в 1С или обновлять уже существующий (см. класс-javadoc).
     */
    @Transactional
    public void trySyncOrder(Order order) {
        if (!oneCClient.isConfigured()) {
            return;
        }
        try {
            sync(order);
        } catch (Exception e) {
            log.warn("Не удалось выгрузить заказ №{} в 1С: {}", order.getId(), e.getMessage());
            order.setOneCSyncError(e.getMessage());
            orderRepository.save(order);
            notifyAdmin(order, e.getMessage());
        }
    }

    /**
     * Вызывается при отмене заказа (см. OrderService.cancel) — ставит в уже выгруженном
     * документе "Реализация" флаг "Пометка удаления", не трогая сам документ иначе (проведён
     * он или нет). Ничего не делает, если заказ в 1С никогда не выгружался.
     */
    @Transactional
    public void trySyncCancel(Order order) {
        if (!oneCClient.isConfigured() || order.getOneCDocumentKey() == null) {
            return;
        }
        try {
            oneCClient.markForDeletion("Document_РеализацияТоваровУслуг", order.getOneCDocumentKey(), true);
            order.setOneCSyncError(null);
            orderRepository.save(order);
            log.info("Заказ №{} отменён — документ №{} в 1С помечен на удаление",
                    order.getId(), order.getOneCDocumentNumber());
        } catch (Exception e) {
            log.warn("Не удалось пометить документ №{} на удаление в 1С (заказ №{}): {}",
                    order.getOneCDocumentNumber(), order.getId(), e.getMessage());
            order.setOneCSyncError(e.getMessage());
            orderRepository.save(order);
            notifyAdmin(order, e.getMessage());
        }
    }

    private void sync(Order order) {
        Client client = order.getClient();
        if (client.getOneCContragentKey() == null || client.getOneCContractKey() == null) {
            String message = "Клиент «" + client.getName() + "» не привязан к 1С — свяжите его в карточке клиента";
            order.setOneCSyncError(message);
            orderRepository.save(order);
            notifyAdmin(order, message);
            return;
        }

        String priceTypeKey = oneCClientPriceSyncService.resolvePriceTypeKey(client);
        if (priceTypeKey == null) {
            String message = "У клиента «" + client.getName() + "» не найден именной тип цен в 1С";
            order.setOneCSyncError(message);
            orderRepository.save(order);
            notifyAdmin(order, message);
            return;
        }

        List<Map<String, Object>> lines = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        int lineNumber = 1;
        for (OrderItem item : order.getItems()) {
            Product product = item.getProduct();
            Map<String, Object> template = resolveLineTemplate(product);
            if (template == null) {
                unresolved.add(product.getName());
                continue;
            }
            Map<String, Object> line = new LinkedHashMap<>(template);
            line.put("LineNumber", String.valueOf(lineNumber++));
            line.put("Номенклатура_Key", product.getOneCNomenclatureKey());
            line.put("Количество", item.getQuantity());
            line.put("Цена", item.getPrice());
            line.put("Сумма", item.getPrice().multiply(item.getQuantity()));
            lines.add(line);
        }

        if (!unresolved.isEmpty()) {
            String message = "Не найдено в 1С по названию (нет истории продаж): " + String.join(", ", unresolved);
            order.setOneCSyncError(message);
            orderRepository.save(order);
            notifyAdmin(order, message);
            return;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        // Время всегда фиксированное 07:00 — так принято в этой базе 1С. Дата — как только заказ
        // отгружен (order.shippedAt проставляется в OrderService.ship), фактическая дата
        // отгрузки; до тех пор — дата создания заказа в CRM (сегодня, если почему-то не
        // проставлена) как временный черновик.
        LocalDate orderDate = order.getShippedAt() != null ? order.getShippedAt().toLocalDate()
                : order.getCreatedAt() != null ? order.getCreatedAt().toLocalDate() : LocalDate.now();
        body.put("Date", ONEC_DATE.format(orderDate.atTime(7, 0)));
        body.put("Posted", false);
        body.put("Организация_Key", organizationKey);
        body.put("Контрагент_Key", client.getOneCContragentKey());
        body.put("ДоговорКонтрагента_Key", client.getOneCContractKey());
        body.put("Склад_Key", warehouseKey);
        body.put("ВалютаДокумента_Key", currencyKey);
        body.put("ВидУчетаНУ_Key", taxAccountingTypeKey);
        body.put("ТипЦен_Key", priceTypeKey);
        body.put("СчетУчетаРасчетовПоАвансам_Key", advanceAccountKey);
        body.put("СчетУчетаРасчетовСКонтрагентом_Key", settlementAccountKey);
        body.put("ВидОперации", "Товары");
        body.put("Комментарий", "Заказ CRM №" + order.getId());
        body.put("Товары", lines);

        if (order.getOneCDocumentKey() == null) {
            OneCClient.CreatedDocument created = oneCClient.createDraftSale(body);
            order.setOneCDocumentKey(created.ref());
            order.setOneCDocumentNumber(created.number());
            order.setOneCSyncError(null);
            orderRepository.save(order);
            log.info("Заказ №{} выгружен в 1С непроведённым документом №{}", order.getId(), created.number());
            postIfShipped(order);
            return;
        }

        if (Boolean.TRUE.equals(oneCClient.fetchPosted("Document_РеализацияТоваровУслуг", order.getOneCDocumentKey()))) {
            String message = "Документ №" + order.getOneCDocumentNumber()
                    + " уже проведён в 1С — новый состав внесите туда вручную";
            order.setOneCSyncError(message);
            orderRepository.save(order);
            notifyAdmin(order, message);
            return;
        }

        oneCClient.updateDraftSale(order.getOneCDocumentKey(), body);
        order.setOneCSyncError(null);
        orderRepository.save(order);
        log.info("Заказ №{} — обновлён состав непроведённого документа №{} в 1С",
                order.getId(), order.getOneCDocumentNumber());
        postIfShipped(order);
    }

    /**
     * Проводит "Реализацию" в 1С — только когда заказ реально отгружен ({@code shippedAt}
     * проставлен в OrderService.ship): до отгрузки документ живёт черновиком и ещё может
     * переехать датой и составом. Проведение — то, чем товар физически списывается со склада в
     * 1С, поэтому и привязано именно к отгрузке.
     *
     * <p>Отдельный шаг, который НЕ роняет синхронизацию: документ уже создан/обновлён, его
     * ссылка сохранена, и откатывать всё из-за неудачного проведения незачем — причина уходит в
     * {@link Order#getOneCSyncError()} и уведомлением админу, а бухгалтер проводит документ в 1С
     * руками. Сама отгрузка в CRM не блокируется в любом случае.
     */
    private void postIfShipped(Order order) {
        if (order.getShippedAt() == null) {
            return;
        }
        try {
            oneCClient.postDocument("Document_РеализацияТоваровУслуг", order.getOneCDocumentKey());
            order.setOneCSyncError(null);
            orderRepository.save(order);
            log.info("Заказ №{} — документ №{} проведён в 1С", order.getId(), order.getOneCDocumentNumber());
        } catch (Exception e) {
            String message = "документ №" + order.getOneCDocumentNumber()
                    + " выгружен, но не проведён в 1С: " + e.getMessage();
            log.warn("Заказ №{} — {}", order.getId(), message);
            order.setOneCSyncError(message);
            orderRepository.save(order);
            notifyAdmin(order, message);
        }
    }

    /**
     * Номенклатура + шаблон счетов учёта кэшируются на товаре при первом успешном подборе —
     * дальше выгрузка этого товара идёт без обращения к 1С за этими данными (см. Product.oneCLineTemplate).
     *
     * <p>Если у самой номенклатуры ещё не было ни одной продажи в 1С (свежий товар),
     * {@link OneCClient#findLineTemplate} вернёт null — в этом случае берём "заготовку"
     * с любой другой продажи ({@link OneCClient#findAnyLineTemplate}) и подставляем в неё
     * номенклатурную группу и саму номенклатуру ЭТОГО товара: счета учёта в этой базе
     * одинаковы для всех товаров, различается только аналитика по группе/номенклатуре
     * (проверено на реальных данных — см. OneCClient class-javadoc и коммит с этим фиксом).
     */
    private Map<String, Object> resolveLineTemplate(Product product) {
        boolean changed = false;
        if (product.getOneCNomenclatureKey() == null) {
            String key = findNomenclatureKeyByName(product.getName());
            if (key == null) return null;
            product.setOneCNomenclatureKey(key);
            changed = true;
        }
        Map<String, Object> template;
        if (product.getOneCLineTemplate() == null) {
            template = oneCClient.findLineTemplate(product.getOneCNomenclatureKey());
            if (template == null) {
                template = buildSyntheticTemplate(product.getOneCNomenclatureKey());
                if (template == null) return null;
            }
            product.setOneCLineTemplate(serializeTemplate(template));
            changed = true;
        } else {
            template = deserializeTemplate(product.getOneCLineTemplate());
        }
        if (changed) productRepository.save(product);
        return template;
    }

    private Map<String, Object> buildSyntheticTemplate(String nomenclatureKey) {
        String groupKey = oneCClient.fetchNomenclatureGroup(nomenclatureKey);
        Map<String, Object> seed = oneCClient.findAnyLineTemplate();
        if (groupKey == null || seed == null) return null;

        Map<String, Object> template = new LinkedHashMap<>(seed);
        template.put("СубконтоДоходовБУ2", groupKey);
        template.put("СубконтоСписанияСебестоимостиБУ2", groupKey);
        template.put("СубконтоДоходовНУ2", nomenclatureKey);
        return template;
    }

    private String findNomenclatureKeyByName(String name) {
        String needle = name.trim().toLowerCase();
        return oneCClient.fetchNomenclatureNames().entrySet().stream()
                .filter(e -> e.getValue().trim().toLowerCase().equals(needle))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    private void notifyAdmin(Order order, String reason) {
        notificationService.notifyRole(Role.ADMIN,
                "Заказ №" + order.getId() + " не выгружен в 1С: " + reason, order);
    }

    // Хранится как простая строка "ключ=значение" через ";" — Map<String,Object> с разнородными
    // типами (String/Number/Boolean) не нужен полноценный JSON, а тянуть ObjectMapper в domain
    // ради одного поля избыточно; типы при чтении не теряются, т.к. значения либо GUID/строки,
    // либо булевы "true"/"false" — числа среди полей шаблона (счета/субконто) не встречаются.
    private static String serializeTemplate(Map<String, Object> template) {
        StringBuilder sb = new StringBuilder();
        template.forEach((k, v) -> {
            if (!sb.isEmpty()) sb.append(';');
            sb.append(k).append('=').append(v == null ? "" : v);
        });
        return sb.toString();
    }

    private static Map<String, Object> deserializeTemplate(String raw) {
        Map<String, Object> template = new LinkedHashMap<>();
        for (String pair : raw.split(";")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            String key = pair.substring(0, eq);
            String value = pair.substring(eq + 1);
            template.put(key, switch (value) {
                case "true" -> Boolean.TRUE;
                case "false" -> Boolean.FALSE;
                case "" -> null;
                default -> value;
            });
        }
        return template;
    }
}
