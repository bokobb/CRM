package com.confectionery.crm.service;

import com.confectionery.crm.api.dto.OrderItemUpdateRequest;
import com.confectionery.crm.domain.*;
import com.confectionery.crm.repository.ClientRepository;
import com.confectionery.crm.repository.NotificationRepository;
import com.confectionery.crm.repository.OrderRepository;
import com.confectionery.crm.repository.ProductRepository;
import com.confectionery.crm.web.dto.OrderForm;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Управляет жизненным циклом дистрибьюторского заказа (= накладной): цена каждой позиции
 * берётся из индивидуального прайса клиента (см. ClientPriceService), подтверждение заказа
 * сразу переводит его в статус "готов к отгрузке" (готовая продукция всегда есть на складе —
 * цеховое производство под заказ не создаётся), а отгрузка склада ГП (ship) фиксирует
 * гос. номер ТС и переводит заказ в финальный статус.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final ClientRepository clientRepository;
    private final ProductRepository productRepository;
    private final ClientPriceService clientPriceService;
    private final NotificationService notificationService;
    private final InvoicePdfService invoicePdfService;
    private final WhatsAppGatewayClient whatsAppGatewayClient;
    private final OneCOrderSyncService oneCOrderSyncService;
    private final OneCQueue oneCQueue;
    private final NotificationRepository notificationRepository;
    private final CurrentUserService currentUserService;
    private final PickupSlotService pickupSlotService;
    private final OrderStateMachine stateMachine;

    public List<Order> findAll() {
        return orderRepository.findAllByOrderByCreatedAtDesc();
    }

    /**
     * Фильтр «В процессе сборки» (NEW) в приложении показывает и ещё не принятые заказы клиентов
     * (PLACED, «Новая») — склад ГП видит их в своём списке по умолчанию и принимает оттуда.
     */
    public List<Order> findByStatus(OrderStatus status) {
        if (status == OrderStatus.NEW) {
            return newestFirst(newestFirst(orderRepository.findByStatusOrderByCreatedAtDesc(OrderStatus.PLACED),
                    orderRepository.findByStatusOrderByCreatedAtDesc(OrderStatus.CONFIRMED)),
                    orderRepository.findByStatusOrderByCreatedAtDesc(OrderStatus.NEW));
        }
        return orderRepository.findByStatusOrderByCreatedAtDesc(status);
    }

    private static List<Order> newestFirst(List<Order> a, List<Order> b) {
        List<Order> all = new java.util.ArrayList<>(a);
        all.addAll(b);
        all.sort(java.util.Comparator.comparing(Order::getCreatedAt).reversed());
        return all;
    }

    public List<Order> findByClient(Long clientId) {
        return orderRepository.findByClientIdOrderByCreatedAtDesc(clientId);
    }

    /** Статусы, при которых заказ считается завершённым и подчиняется обычному фильтру по дате. */
    private static final List<OrderStatus> FINAL_STATUSES = List.of(OrderStatus.COMPLETED, OrderStatus.CANCELLED);

    /**
     * Заказы за диапазон дат [from, to] включительно — по дате отгрузки, а у ещё не отгруженных
     * по дате создания — для фильтра по дате в приложении. Незавершённые заказы (см. FINAL_STATUSES) возвращаются всегда, даже если их
     * дата создания вне диапазона — см. комментарий к OrderRepository.findForDateRange.
     */
    public List<Order> findByDateRange(LocalDate from, LocalDate to) {
        return orderRepository.findForDateRange(null, from.atStartOfDay(), to.plusDays(1).atStartOfDay(), FINAL_STATUSES);
    }

    public List<Order> findByStatusAndDateRange(OrderStatus status, LocalDate from, LocalDate to) {
        if (status == OrderStatus.NEW) {
            // как в findByStatus: «В процессе сборки» вместе с «Новая» и «Принят»
            return newestFirst(newestFirst(
                    orderRepository.findForDateRange(OrderStatus.PLACED, from.atStartOfDay(), to.plusDays(1).atStartOfDay(), FINAL_STATUSES),
                    orderRepository.findForDateRange(OrderStatus.CONFIRMED, from.atStartOfDay(), to.plusDays(1).atStartOfDay(), FINAL_STATUSES)),
                    orderRepository.findForDateRange(OrderStatus.NEW, from.atStartOfDay(), to.plusDays(1).atStartOfDay(), FINAL_STATUSES));
        }
        return orderRepository.findForDateRange(status, from.atStartOfDay(), to.plusDays(1).atStartOfDay(), FINAL_STATUSES);
    }

    public Order findById(Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Заказ не найден: " + id));
    }

    @Transactional
    public Order createOrder(OrderForm form) {
        Client client = clientRepository.findById(form.getClientId())
                .orElseThrow(() -> new IllegalArgumentException("Клиент не найден: " + form.getClientId()));

        Order order = new Order();
        order.setClient(client);
        order.setDueDate(form.getDueDate());
        order.setNotes(form.getNotes());
        if (form.getPickupHour() != null) {
            pickupSlotService.check(form.getDueDate(), form.getPickupHour(), null);
            order.setPickupHour(form.getPickupHour());
        }
        // Заказ клиента (сайт, WhatsApp) сначала «Новая» — его принимает админ или склад ГП (accept);
        // заказ сотрудника сразу «Принят», сборку начинает склад ГП (startAssembly).
        boolean byClient = form.isFromShop() || form.isFromWhatsapp();
        order.setStatus(byClient ? OrderStatus.PLACED : OrderStatus.CONFIRMED);
        order.setPaymentMethod(form.getPaymentMethod() != null ? form.getPaymentMethod()
                : byClient ? null : client.getDefaultPayment());
        order.setSource(form.isFromShop() ? OrderSource.SITE : form.isFromWhatsapp() ? OrderSource.WHATSAPP : OrderSource.CRM);
        if (!byClient) {
            order.setAcceptedAt(order.getCreatedAt());
        }
        order.setFromWhatsapp(form.isFromWhatsapp());

        for (OrderForm.OrderItemForm itemForm : form.getItems()) {
            if (itemForm.getProductId() == null || itemForm.getQuantity() == null) {
                continue;
            }
            Product product = findSellableProduct(itemForm.getProductId());
            if (form.isFromShop() && !product.isVisibleToClients()) {
                throw new IllegalStateException("Товар «" + product.getName() + "» временно недоступен для заказа");
            }
            OrderItem item = new OrderItem();
            item.setProduct(product);
            item.setQuantity(itemForm.getQuantity());
            item.setPrice(clientPriceService.resolvePrice(client, product));
            order.addItem(item);
        }

        if (order.getItems().isEmpty()) {
            throw new IllegalArgumentException("В заказе должна быть хотя бы одна позиция");
        }

        log(order, OrderEventType.STATUS, order.getStatus(), null,
                form.isFromShop() ? "Покупатель на сайте" : form.isFromWhatsapp() ? "Покупатель в WhatsApp" : actor());
        order = orderRepository.save(order);
        notificationService.notifyRole(Role.WAREHOUSE_FG,
                "Новый заказ №" + order.getId() + " (" + client.getName() + ") — нужно собрать", order);
        notificationService.notifyRole(Role.ACCOUNTANT,
                "Новый заказ №" + order.getId() + " (" + client.getName() + ") на сумму " + order.getTotalPrice(), order);
        // ADMIN — только если заказ создал сам клиент (витрина /shop или WhatsApp), без участия
        // сотрудника: тогда админу нужен присмотр. Если заказ завёл сотрудник (менеджер/админ
        // через веб или мобильное приложение) — он и так в курсе, отдельный push не нужен.
        if (form.isFromShop() || form.isFromWhatsapp()) {
            notificationService.notifyRole(Role.ADMIN,
                    "Новый заказ №" + order.getId() + " (" + client.getName() + ") на сумму " + order.getTotalPrice()
                            + " — создан клиентом", order);
        }
        oneCQueue.order(order);
        return order;
    }

    /** Заказ, оформленный клиентом (PLACED, «Новая»), принят — клиент на /shop видит «Принят», дальше сборка. */
    @Transactional
    public Order accept(Long orderId) {
        Order order = findById(orderId);
        if (order.getStatus() != OrderStatus.PLACED) {
            throw new IllegalStateException("Принять можно только новый заказ");
        }
        order.setStatus(OrderStatus.CONFIRMED);
        order.setAcceptedAt(LocalDateTime.now());
        log(order, OrderEventType.STATUS, OrderStatus.CONFIRMED, null);
        return order;
    }

    /** Склад ГП начал собирать принятый заказ: CONFIRMED «Принят» -> NEW «В процессе сборки». */
    @Transactional
    public Order startAssembly(Long orderId) {
        Order order = findById(orderId);
        if (order.getStatus() != OrderStatus.CONFIRMED) {
            throw new IllegalStateException("Начать сборку можно только принятого заказа");
        }
        order.setStatus(OrderStatus.NEW);
        log(order, OrderEventType.STATUS, OrderStatus.NEW, null);
        return order;
    }

    /**
     * Сборка завершена: готовая продукция всегда в наличии, поэтому заказ сразу готов к отгрузке.
     * Новый заказ (PLACED) можно отметить собранным сразу — тогда он заодно считается принятым.
     */
    @Transactional
    public Order confirm(Long orderId) {
        Order order = findById(orderId);
        OrderStatus from = order.getStatus();
        if (from != OrderStatus.NEW && from != OrderStatus.PLACED && from != OrderStatus.CONFIRMED
                && from != OrderStatus.IN_PRODUCTION) {
            throw new IllegalStateException("Отметить собранным можно только заказ в процессе сборки");
        }
        LocalDateTime now = LocalDateTime.now();
        if (order.getAcceptedAt() == null) {
            order.setAcceptedAt(now);
        }
        order.setStatus(OrderStatus.READY);
        order.setReadyAt(now);
        log(order, OrderEventType.STATUS, OrderStatus.READY, null);
        String message = "Заказ №" + order.getId() + " (" + order.getClient().getName() + ") собран, готов к отгрузке";
        notificationService.notifyRole(Role.ACCOUNTANT, message, order);
        notificationService.notifyRole(Role.ADMIN, message, order);
        return order;
    }

    /**
     * Заказ в статусе READY ("Готов к отгрузке") возвращается обратно в NEW ("В процессе
     * сборки") по кнопке "Редактировать состав" — дальше состав редактируется тем же
     * чек-листом (чекбоксы + изменить количество + удалить + добавить позицию), что и при
     * первичной сборке (см. orders/view.html, статус NEW), а не отдельной страницей/диалогом.
     * Без уведомлений — по явной просьбе пользователя ("push не отправлять никому при
     * READY->NEW"). {@link #confirm} (обратный переход NEW->READY) не имеет других побочных
     * эффектов кроме смены статуса и уведомлений (не списывает остатки, не синхронизирует
     * с 1С — это происходит только в ship()/updateItems()),
     * поэтому обратный переход тоже достаточно сделать простой сменой статуса, ничего не
     * откатывая.
     */
    @Transactional
    public Order reopenForEditing(Long orderId) {
        Order order = findById(orderId);
        if (order.getStatus() != OrderStatus.READY) {
            throw new IllegalStateException("Вернуть в сборку можно только заказ в статусе «Готов к отгрузке»");
        }
        order.setStatus(OrderStatus.NEW);
        order.setReadyAt(null);
        log(order, OrderEventType.STATUS, OrderStatus.NEW, "Вернули в сборку");
        return order;
    }

    /**
     * Админ откатывает заказ на шаг назад — исправить ошибочно выставленный статус:
     * Завершён -> Отгружен -> Готов к отгрузке -> В процессе сборки; Отменён -> В процессе сборки.
     * Откат отгрузки снимает её следы: товар, списанный отгрузкой через CRM, возвращается на
     * остаток, а "Реализация" в 1С распроводится очередью (не отгруженный заказ = черновик, см.
     * OneCOrderSyncService). Без уведомлений — это исправление, а не событие.
     */
    @Transactional
    public Order rollback(Long orderId) {
        Order order = findById(orderId);
        OrderStatus was = order.getStatus();
        switch (was) {
            case COMPLETED -> order.setStatus(OrderStatus.DELIVERED);
            case DELIVERED -> {
                if (order.getVehicleNumber() != null) {
                    for (OrderItem item : order.getItems()) {
                        Product product = item.getProduct();
                        product.setQuantityInStock(product.getQuantityInStock().add(item.getQuantity()));
                        productRepository.save(product);
                    }
                }
                order.setStatus(OrderStatus.READY);
                order.setVehicleNumber(null);
                order.setShippedAt(null);
                order.setShippedByName(null);
                oneCQueue.order(order);
            }
            case NO_SHOW -> order.setStatus(OrderStatus.READY);
            case READY -> {
                order.setStatus(OrderStatus.NEW);
                order.setReadyAt(null);
            }
            case NEW, IN_PRODUCTION -> order.setStatus(OrderStatus.CONFIRMED);
            case CANCELLED -> {
                // Отмена уже вернула списанный товар и распровела документ. Заказ возвращается в
                // статус, из которого его отменили, если это был не выданный; иначе — снова в сборку.
                OrderStatus before = statusBeforeCancel(order);
                order.setStatus(before != null ? before : OrderStatus.NEW);
                if (order.getStatus() == OrderStatus.NEW || order.getStatus() == OrderStatus.CONFIRMED) {
                    order.setReadyAt(null);
                }
                if (order.getAcceptedAt() == null && order.getStatus() != OrderStatus.PLACED) {
                    order.setAcceptedAt(LocalDateTime.now());
                }
                order.setVehicleNumber(null);
                order.setShippedAt(null);
                order.setShippedByName(null);
                oneCQueue.order(order);
            }
            case CONFIRMED -> {
                // Заказ клиента возвращается в «Новая» (ещё не принят); заказ сотрудника начинается с «Принят».
                if (order.getSource() != OrderSource.SITE && order.getSource() != OrderSource.WHATSAPP) {
                    throw new IllegalStateException("Заказ уже в начальном статусе — откатывать некуда");
                }
                order.setStatus(OrderStatus.PLACED);
                order.setAcceptedAt(null);
            }
            case PLACED -> throw new IllegalStateException("Заказ уже в начальном статусе — откатывать некуда");
        }
        log(order, OrderEventType.STATUS, order.getStatus(), "Вернули статус «" + was.badgeLabel() + "» на шаг назад");
        return order;
    }

    /** Статус заказа перед отменой — по журналу; null, если журнала нет или отменили выданный заказ. */
    public static OrderStatus statusBeforeCancel(Order order) {
        OrderStatus prev = null, beforeLastCancel = null;
        for (OrderEvent e : order.getEvents()) {
            if (e.getType() != OrderEventType.STATUS || e.getStatus() == null) continue;
            if (e.getStatus() == OrderStatus.CANCELLED) beforeLastCancel = prev;
            prev = e.getStatus();
        }
        if (beforeLastCancel == null || beforeLastCancel.isShipped() || beforeLastCancel == OrderStatus.CANCELLED) return null;
        return beforeLastCancel;
    }

    /**
     * Полное редактирование состава заказа — и корректировка при сборке склада ГП (часть
     * товара может оказаться не в наличии или в меньшем количестве, чем заказано — количество
     * можно уменьшить, позиция с количеством <= 0 удаляется из заказа целиком), и смена товара
     * в позиции (productId), если ИИ при разборе заказа из WhatsApp ошибся с распознаванием
     * (например, спутал "Коржик" и "Коржик Сдоба") — цена пересчитывается по прайсу клиента
     * для нового товара, и добавление новых позиций (update с itemId == null, тогда productId
     * обязателен). Доступно, пока заказ ещё не отгружен/не закрыт/не отменён. Если заказ уже
     * выгружен в 1С непроведённым документом — новый состав перезаписывается туда же (см.
     * OneCOrderSyncService.trySyncOrder); если документ уже кто-то провёл в 1С руками, синхронизация
     * останавливается с ошибкой вместо того, чтобы разойтись с проводками.
     */
    @Transactional
    public Order updateItems(Long orderId, List<OrderItemUpdateRequest.ItemUpdate> updates) {
        return updateItems(orderId, updates, false);
    }

    /**
     * {@code admin} — админ может менять состав и отгруженного/завершённого заказа: остаток
     * склада ГП поправляется на разницу (если отгрузка списывала его через CRM, см. ship), а
     * проведённая "Реализация" в 1С сначала распроводится, обновляется и проводится заново (см.
     * OneCOrderSyncService.trySyncOrder с repost).
     */
    @Transactional
    public Order updateItems(Long orderId, List<OrderItemUpdateRequest.ItemUpdate> updates, boolean admin) {
        Order order = findById(orderId);
        boolean shipped = order.getStatus() == OrderStatus.DELIVERED || order.getStatus() == OrderStatus.COMPLETED;
        if (order.getStatus() == OrderStatus.CANCELLED || (shipped && !admin)) {
            throw new IllegalStateException("Состав заказа в этом статусе изменить нельзя");
        }
        // Отгрузка через CRM уже списала товар со склада (см. ship) — правка состава после неё
        // двигает остаток на разницу. Импортированные из 1С отгрузки остаток в CRM не трогали.
        boolean stockWrittenOff = shipped && order.getVehicleNumber() != null;
        Map<Product, BigDecimal> before = stockWrittenOff ? quantitiesByProduct(order) : Map.of();
        for (OrderItemUpdateRequest.ItemUpdate update : updates) {
            if (update.itemId() == null) {
                if (update.productId() == null || update.quantity().signum() <= 0) {
                    continue;
                }
                Product product = findSellableProduct(update.productId());
                OrderItem newItem = new OrderItem();
                newItem.setProduct(product);
                newItem.setQuantity(update.quantity());
                newItem.setPrice(clientPriceService.resolvePrice(order.getClient(), product));
                order.addItem(newItem);
                continue;
            }
            OrderItem item = order.getItems().stream()
                    .filter(i -> i.getId().equals(update.itemId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Позиция не найдена в заказе: " + update.itemId()));
            if (update.quantity().signum() <= 0) {
                order.getItems().remove(item);
                continue;
            }
            if (update.productId() != null && !update.productId().equals(item.getProduct().getId())) {
                Product product = findSellableProduct(update.productId());
                item.setProduct(product);
                item.setPrice(clientPriceService.resolvePrice(order.getClient(), product));
            }
            item.setQuantity(update.quantity());
        }
        if (order.getItems().isEmpty()) {
            throw new IllegalArgumentException("В заказе должна остаться хотя бы одна позиция");
        }
        if (stockWrittenOff) {
            Map<Product, BigDecimal> after = quantitiesByProduct(order);
            Set<Product> products = new LinkedHashSet<>(before.keySet());
            products.addAll(after.keySet());
            for (Product product : products) {
                BigDecimal diff = after.getOrDefault(product, BigDecimal.ZERO)
                        .subtract(before.getOrDefault(product, BigDecimal.ZERO));
                if (diff.signum() != 0) {
                    product.setQuantityInStock(product.getQuantityInStock().subtract(diff));
                    productRepository.save(product);
                }
            }
        }
        log(order, OrderEventType.EDIT, null, "состав");
        order = orderRepository.save(order);
        oneCQueue.order(order);
        return order;
    }

    private static Map<Product, BigDecimal> quantitiesByProduct(Order order) {
        Map<Product, BigDecimal> result = new LinkedHashMap<>();
        for (OrderItem item : order.getItems()) {
            result.merge(item.getProduct(), item.getQuantity(), BigDecimal::add);
        }
        return result;
    }

    /**
     * Товар для НОВОЙ позиции заказа — только из продаваемых: архивный (в т.ч. автоматически
     * скрытый, потому что в 1С номенклатура помечена на удаление, см. OneCStockSyncService)
     * продавать нельзя, даже если клиент прислал его id из закешированного списка. Уже
     * существующие позиции с таким товаром при правке количества не трогаем.
     */
    private Product findSellableProduct(Long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("Товар не найден: " + productId));
        if (!product.isActive()) {
            throw new IllegalStateException("Товар «" + product.getName() + "» снят с продажи");
        }
        return product;
    }

    /** Отмена ещё не отгруженного заказа — см. {@link #cancel(Long, boolean)}. */
    @Transactional
    public Order cancel(Long orderId) {
        return cancel(orderId, false);
    }

    /**
     * Отмена заказа: всё, что он сделал, откатывается — документ "Реализация" в 1С распроводится
     * и остаётся черновиком (см. OneCOrderSyncService.trySyncCancel), списанный при отгрузке
     * товар возвращается на остаток. Удалить отменённый заказ целиком — {@link #delete}.
     * Отгруженный/завершённый заказ отменяется только при {@code allowShipped} (админ/бухгалтер).
     */
    @Transactional
    public Order cancel(Long orderId, boolean allowShipped) {
        return cancel(orderId, allowShipped, null);
    }

    /** {@code reason} — причина и комментарий из окна отмены в админке, пишется в историю. */
    @Transactional
    public Order cancel(Long orderId, boolean allowShipped, String reason) {
        Order order = findById(orderId);
        if (order.getStatus() == OrderStatus.CANCELLED) {
            throw new IllegalStateException("Заказ уже отменён");
        }
        boolean shipped = order.getStatus() == OrderStatus.DELIVERED || order.getStatus() == OrderStatus.COMPLETED;
        if (shipped && !allowShipped) {
            throw new IllegalStateException("Отгруженный заказ может отменить только админ или бухгалтер");
        }
        // Остаток списывался только отгрузкой через CRM (см. ship — там же заполняется гос.
        // номер); заказы, импортированные из 1С уже проведёнными, остаток в CRM не трогали.
        if (shipped && order.getVehicleNumber() != null) {
            for (OrderItem item : order.getItems()) {
                Product product = item.getProduct();
                product.setQuantityInStock(product.getQuantityInStock().add(item.getQuantity()));
                productRepository.save(product);
            }
        }
        order.setStatus(OrderStatus.CANCELLED);
        log(order, OrderEventType.STATUS, OrderStatus.CANCELLED, reason);
        notificationService.notify(recipientOf(order), "Заказ #" + order.getId() + " отменён", order);
        String cancelMessage = "Заказ №" + order.getId() + " (" + order.getClient().getName() + ") отменён";
        notificationService.notifyRole(Role.ACCOUNTANT, cancelMessage, order);
        notificationService.notifyRole(Role.ADMIN, cancelMessage, order);
        oneCQueue.order(order);
        return order;
    }

    /**
     * Удаление отменённого заказа: документ в 1С помечается на удаление (сначала — там же, и
     * если 1С не ответила, заказ в CRM остаётся), закреплённые возвраты открепляются (возврат —
     * отдельный документ, живёт дальше), уведомления по заказу удаляются вместе с ним.
     */
    @Transactional
    public void delete(Long orderId) {
        Order order = findById(orderId);
        if (order.getStatus() != OrderStatus.CANCELLED) {
            throw new IllegalStateException("Удалить можно только отменённый заказ — сначала отмените его");
        }
        oneCOrderSyncService.deleteOrThrow(order);
        for (ProductReturn productReturn : order.getReturns()) {
            productReturn.setOrder(null);
        }
        order.getReturns().clear();
        notificationRepository.deleteAll(notificationRepository.findByOrderId(order.getId()));
        orderRepository.delete(order);
    }

    /** Отгрузка складом ГП: фиксирует гос. номер ТС и переводит заказ в статус "отгружен". */
    @Transactional
    public Order ship(Long orderId, String vehicleNumber, String shippedByName) {
        Order order = findById(orderId);
        if (order.getStatus() != OrderStatus.READY && order.getStatus() != OrderStatus.NO_SHOW) {
            throw new IllegalStateException("Отгрузить можно только заказ, готовый к отгрузке");
        }
        if (vehicleNumber == null || vehicleNumber.isBlank()) {
            throw new IllegalArgumentException("Укажите гос. номер ТС");
        }
        order.setStatus(OrderStatus.DELIVERED);
        order.setVehicleNumber(vehicleNumber.trim());
        order.setShippedAt(LocalDateTime.now());
        order.setShippedByName(shippedByName);
        log(order, OrderEventType.STATUS, OrderStatus.DELIVERED, "Гос. номер ТС: " + order.getVehicleNumber(),
                shippedByName != null && !shippedByName.isBlank() ? shippedByName : actor());
        for (OrderItem item : order.getItems()) {
            Product product = item.getProduct();
            product.setQuantityInStock(product.getQuantityInStock().subtract(item.getQuantity()));
        }
        String message = "Заказ " + order.getClient().getName() + " " + order.getVehicleNumber() + " отгружен";
        notificationService.notifyRole(Role.ACCOUNTANT, message, order);
        notificationService.notifyRole(Role.ADMIN, message, order);
        // Документ в 1С уже существует непроведённым черновиком (создан при оформлении заказа —
        // с датой создания заказа), но дата документа должна быть датой ФАКТИЧЕСКОЙ отгрузки —
        // shippedAt выше уже проставлен, поэтому trySyncOrder здесь перезапишет дату на неё
        // и следом проведёт документ — товар списывается со склада в 1С именно проведением
        // (см. OneCOrderSyncService.postIfShipped). Отгрузку в CRM неудача в 1С не блокирует.
        oneCQueue.order(order);
        // Накладная строится из документа 1С — поэтому её шлём, когда очередь отправит заказ в
        // 1С (см. onOrderSynced), с датой отгрузки.
        invoiceAfterSync.add(order.getId());
        return order;
    }

    /** Отгруженные заказы, которым после отправки в 1С нужно послать накладную клиенту. */
    private final java.util.Set<Long> invoiceAfterSync = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @org.springframework.context.event.EventListener
    @Transactional
    public void onOrderSynced(OneCQueue.OrderSynced event) {
        if (invoiceAfterSync.remove(event.orderId())) {
            orderRepository.findById(event.orderId()).ifPresent(this::sendInvoiceToClient);
        }
    }

    /**
     * Накладную клиенту в WhatsApp шлём best-effort: если номер телефона не указан или
     * шлюз недоступен — просто пропускаем, отгрузка при этом уже состоялась и откатывать
     * её из-за проблемы с отправкой документа не нужно.
     */
    private void sendInvoiceToClient(Order order) {
        String phone = order.getClient().getPhone();
        if (phone == null || phone.isBlank()) {
            return;
        }
        try {
            byte[] pdf = invoicePdfService.generate(order);
            String caption = "Накладная № " + invoiceNumber(order) + ". Гос. номер ТС: " + order.getVehicleNumber();
            whatsAppGatewayClient.sendDocument(phone, pdf, invoiceFileName(order), caption);
        } catch (Exception e) {
            log.warn("Не удалось отправить накладную по заказу №{} клиенту в WhatsApp: {}", order.getId(), e.getMessage());
        }
    }

    /** Номер накладной — номер "Реализации" в 1С, как на самой накладной; до выгрузки в 1С — номер заказа. */
    public static String invoiceNumber(Order order) {
        return order.getOneCDocumentNumber() != null
                ? OneCInvoiceService.printNumber(order.getOneCDocumentNumber())
                : String.valueOf(order.getId());
    }

    /**
     * Имя файла накладной для пересылки: "Заказ[_Возврат]_Покупатель_дата.pdf". "Возврат" — если
     * к заказу закреплены возвраты (они печатаются в той же накладной); дата — отгрузки, а до неё
     * — создания заказа.
     */
    public static String invoiceFileName(Order order) {
        StringBuilder name = new StringBuilder("Заказ");
        if (!order.getReturns().isEmpty()) {
            name.append("_Возврат");
        }
        String client = order.getClient() != null ? order.getClient().getName() : null;
        if (client != null && !client.isBlank()) {
            name.append('_').append(client.replaceAll("[\\\\/:*?\"<>|\\s]+", " ").trim());
        }
        LocalDateTime date = order.getShippedAt() != null ? order.getShippedAt() : order.getCreatedAt();
        if (date != null) {
            name.append('_').append(date.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")));
        }
        return name + ".pdf";
    }

    /**
     * Бухгалтер закрывает заказ после отгрузки (сверка оплаты и т.п.). Если на заказе висел
     * непогашенный долг — считаем его автоматически оплаченным: кнопка "Завершить заказ" одна
     * и на неё не нужно отдельно жать "Оплачено" (см. также setDebt/markDebtPaid).
     */
    @Transactional
    public Order complete(Long orderId) {
        Order order = findById(orderId);
        if (order.getStatus() != OrderStatus.DELIVERED) {
            throw new IllegalStateException("Завершить можно только отгруженный заказ");
        }
        order.setStatus(OrderStatus.COMPLETED);
        if (order.getDebtDueDate() != null) {
            order.setDebtPaid(true);
        }
        log(order, OrderEventType.STATUS, OrderStatus.COMPLETED, "Заказ закрыт");
        notificationService.notifyRole(Role.ADMIN,
                "Заказ №" + order.getId() + " (" + order.getClient().getName() + ") завершён", order);
        return order;
    }

    /** Бухгалтер отмечает заказ как неоплаченный — вместо (или до) "Завершить заказ". */
    @Transactional
    public Order setDebt(Long orderId, LocalDate dueDate) {
        if (dueDate == null) {
            throw new IllegalArgumentException("Укажите срок оплаты");
        }
        Order order = findById(orderId);
        order.setDebtDueDate(dueDate);
        order.setDebtPaid(false);
        return order;
    }

    @Transactional
    public Order markDebtPaid(Long orderId) {
        Order order = findById(orderId);
        order.setDebtPaid(true);
        if (order.getPaidAt() == null) {
            order.setPaidAt(LocalDateTime.now());
            order.setPaidByName(actor());
        }
        log(order, OrderEventType.PAY, null, "Долг погашен");
        return order;
    }

    public List<Order> findUnpaidDebts() {
        return orderRepository.findUnpaidDebts();
    }

    public List<Order> findOverdueDebts() {
        return orderRepository.findOverdueDebts(LocalDate.now());
    }

    // ---------------------------------------------------------------- админка: переходы и события

    /**
     * Сменить статус из админки: сначала OrderStateMachine.check (переход и роль), потом тот же
     * метод сервиса, что у приложения. {@code expected} — статус, который видел сотрудник: если
     * заказ уже успели изменить в другой вкладке, бросаем ConcurrentChangeException.
     */
    @Transactional
    public Order transition(Long orderId, OrderStatus target, OrderStatus expected, String vehicleNumber, String reason) {
        Order order = findById(orderId);
        if (expected != null && order.getStatus() != expected) {
            throw new ConcurrentChangeException();
        }
        Role role = currentUserService.role().orElse(null);
        String refusal = stateMachine.check(order, target, role);
        if (refusal != null) {
            throw new IllegalStateException(refusal);
        }
        return switch (target) {
            case CONFIRMED -> accept(orderId);
            case NEW -> order.getStatus() == OrderStatus.READY ? reopenForEditing(orderId) : startAssembly(orderId);
            case READY -> confirm(orderId);
            case NO_SHOW -> noShow(orderId);
            case DELIVERED -> ship(orderId, vehicleNumber, actor());
            case COMPLETED -> complete(orderId);
            case CANCELLED -> cancel(orderId, role == Role.ADMIN || role == Role.ACCOUNTANT, reason);
            default -> throw new IllegalStateException("Такого перехода нет");
        };
    }

    /** Заказ изменили в другой вкладке или на другом устройстве — показать свежие данные. */
    public static class ConcurrentChangeException extends RuntimeException {
        public ConcurrentChangeException() {
            super("Заказ уже изменил другой сотрудник — показали свежие данные");
        }
    }

    /** Окно выдачи прошло, клиент не приехал: READY -> NO_SHOW. */
    @Transactional
    public Order noShow(Long orderId) {
        Order order = findById(orderId);
        if (order.getStatus() != OrderStatus.READY) {
            throw new IllegalStateException("«Клиент не пришёл» можно отметить только у готового заказа");
        }
        order.setStatus(OrderStatus.NO_SHOW);
        log(order, OrderEventType.STATUS, OrderStatus.NO_SHOW,
                order.getPickupHour() != null ? "Окно выдачи закончилось, клиент не приехал" : "Клиент не приехал");
        notificationService.notifyRole(Role.ADMIN,
                "Заказ №" + order.getId() + " (" + order.getClient().getName() + ") не забрали", order);
        return order;
    }

    /** Перенести выдачу на другой день и окно; «Не забрали» после переноса снова «Готов к выдаче». */
    @Transactional
    public Order move(Long orderId, LocalDate day, Integer hour) {
        Order order = findById(orderId);
        Role role = currentUserService.role().orElse(null);
        if (!stateMachine.canMove(order, role)) {
            throw new IllegalStateException("Перенести выдачу этого заказа нельзя");
        }
        pickupSlotService.check(day, hour, order.getId());
        String was = pickupText(order.getDueDate(), order.getPickupHour());
        order.setDueDate(day);
        order.setPickupHour(hour);
        if (order.getStatus() == OrderStatus.NO_SHOW) {
            order.setStatus(OrderStatus.READY);
        }
        log(order, OrderEventType.MOVE, null, "было " + was + " → " + pickupText(day, hour));
        oneCQueue.order(order);
        return order;
    }

    private static String pickupText(LocalDate day, Integer hour) {
        if (day == null) return "без даты";
        String d = day.format(DateTimeFormatter.ofPattern("d MMM", new java.util.Locale("ru"))).replace(".", "");
        return hour == null ? d : d + ", " + PickupSlotService.label(hour);
    }

    /**
     * Отметить оплату (директор, бухгалтер). У выданного заказа это и закрытие: DELIVERED ->
     * COMPLETED, как раньше «Завершить заказ» с погашением долга.
     */
    @Transactional
    public Order markPaid(Long orderId) {
        Order order = findById(orderId);
        Role role = currentUserService.role().orElse(null);
        if (!stateMachine.canMarkPaid(order, role)) {
            throw new IllegalStateException("Отметить оплату этого заказа нельзя");
        }
        order.setPaidAt(LocalDateTime.now());
        order.setPaidByName(actor());
        if (order.getDebtDueDate() != null) {
            order.setDebtPaid(true);
        }
        String method = order.getPaymentMethod() != null ? order.getPaymentMethod().getLabel() : "Оплата";
        log(order, OrderEventType.PAY, null, method + ", " + money(order.getNetTotal()));
        if (order.getStatus() == OrderStatus.DELIVERED) {
            order.setStatus(OrderStatus.COMPLETED);
            log(order, OrderEventType.STATUS, OrderStatus.COMPLETED, "Выдан и оплачен");
        }
        return order;
    }

    private static String money(BigDecimal v) {
        java.text.DecimalFormatSymbols sym = new java.text.DecimalFormatSymbols(new java.util.Locale("ru"));
        sym.setGroupingSeparator('\u00A0');
        return new java.text.DecimalFormat("#,##0", sym).format(v) + "\u00A0₸";
    }

    /** Одна строка формы заказа админки: товар и количество в штуках. */
    public record Line(Long productId, BigDecimal quantity) {
    }

    /**
     * Изменить заказ из админки (пока «Новая» или «Принят»): клиент, окно выдачи, состав, оплата,
     * комментарий. У позиций, которые остались с тем же товаром, цена прежняя; у новых — из прайса
     * клиента на сегодня. {@code expectedStatus}/{@code expectedStamp} — что видел сотрудник.
     */
    @Transactional
    public Order updateOrder(Long orderId, String expectedStamp, Long clientId, LocalDate day, Integer hour,
                             List<Line> lines, PaymentMethod payment, String notes) {
        Order order = findById(orderId);
        if (expectedStamp != null && !expectedStamp.equals(stamp(order))) {
            throw new ConcurrentChangeException();
        }
        Role role = currentUserService.role().orElse(null);
        if (!stateMachine.canEdit(order, role)) {
            throw new IllegalStateException("Изменить можно только заказ «Новая» или «Принят» — до начала сборки");
        }
        List<String> changed = new java.util.ArrayList<>();
        Client client = clientRepository.findById(clientId)
                .orElseThrow(() -> new IllegalArgumentException("Выберите клиента."));
        if (!client.getId().equals(order.getClient().getId())) {
            order.setClient(client);
            changed.add("клиент");
        }
        if (!java.util.Objects.equals(day, order.getDueDate()) || !java.util.Objects.equals(hour, order.getPickupHour())) {
            pickupSlotService.check(day, hour, order.getId());
            order.setDueDate(day);
            order.setPickupHour(hour);
            changed.add("окно выдачи");
        }
        Map<Long, BigDecimal> oldPrice = new java.util.HashMap<>();
        Map<Long, BigDecimal> oldQty = new java.util.HashMap<>();
        for (OrderItem item : order.getItems()) {
            oldPrice.putIfAbsent(item.getProduct().getId(), item.getPrice());
            oldQty.merge(item.getProduct().getId(), item.getQuantity(), BigDecimal::add);
        }
        Map<Long, BigDecimal> newQty = new LinkedHashMap<>();
        for (Line line : lines) {
            if (line.productId() == null || line.quantity() == null || line.quantity().signum() <= 0) continue;
            newQty.merge(line.productId(), line.quantity(), BigDecimal::add);
        }
        if (newQty.isEmpty()) {
            throw new IllegalArgumentException("Добавьте хотя бы один товар с количеством.");
        }
        if (!newQty.equals(oldQty) || changed.contains("клиент")) {
            order.getItems().clear();
            for (Map.Entry<Long, BigDecimal> e : newQty.entrySet()) {
                Product product = oldPrice.containsKey(e.getKey())
                        ? productRepository.findById(e.getKey()).orElseThrow()
                        : findSellableProduct(e.getKey());
                OrderItem item = new OrderItem();
                item.setProduct(product);
                item.setQuantity(e.getValue());
                item.setPrice(oldPrice.containsKey(e.getKey()) && !changed.contains("клиент")
                        ? oldPrice.get(e.getKey()) : clientPriceService.resolvePrice(client, product));
                order.addItem(item);
            }
            changed.add("состав");
        }
        if (payment != order.getPaymentMethod()) {
            order.setPaymentMethod(payment);
            changed.add("оплата");
        }
        String n = notes == null || notes.isBlank() ? null : notes.trim();
        if (!java.util.Objects.equals(n, order.getNotes())) {
            order.setNotes(n);
            changed.add("комментарий");
        }
        if (!changed.isEmpty()) {
            log(order, OrderEventType.EDIT, null, String.join(", ", changed));
            order = orderRepository.save(order);
            oneCQueue.order(order);
        }
        return order;
    }

    /** Отпечаток заказа для формы изменения: статус и состав — если поменялись, заказ изменили без нас. */
    public static String stamp(Order order) {
        StringBuilder sb = new StringBuilder(order.getStatus().name());
        order.getItems().stream()
                .sorted(java.util.Comparator.comparing(i -> i.getProduct().getId()))
                .forEach(i -> sb.append('|').append(i.getProduct().getId()).append(':')
                        .append(i.getQuantity().stripTrailingZeros().toPlainString()));
        sb.append('|').append(order.getDueDate()).append('|').append(order.getPickupHour());
        return Integer.toHexString(sb.toString().hashCode());
    }

    // ---------------------------------------------------------------- журнал

    private String actor() {
        String name = currentUserService.displayName();
        return name != null ? name : "Сотрудник";
    }

    private void log(Order order, OrderEventType type, OrderStatus status, String note) {
        log(order, type, status, note, actor());
    }

    private static void log(Order order, OrderEventType type, OrderStatus status, String note, String by) {
        OrderEvent e = new OrderEvent();
        e.setOrder(order);
        e.setType(type);
        e.setStatus(status);
        e.setAt(LocalDateTime.now());
        e.setByName(by);
        e.setNote(note == null || note.isBlank() ? null : note.length() > 500 ? note.substring(0, 500) : note);
        order.getEvents().add(e);
    }

    static String recipientOf(Order order) {
        Client client = order.getClient();
        if (client.getEmail() != null && !client.getEmail().isBlank()) {
            return client.getEmail();
        }
        if (client.getPhone() != null && !client.getPhone().isBlank()) {
            return client.getPhone();
        }
        return client.getName();
    }
}
