package com.confectionery.crm.service;

import com.confectionery.crm.api.dto.OrderItemUpdateRequest;
import com.confectionery.crm.domain.*;
import com.confectionery.crm.repository.ClientRepository;
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
import java.util.List;

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

    public List<Order> findAll() {
        return orderRepository.findAllByOrderByCreatedAtDesc();
    }

    public List<Order> findByStatus(OrderStatus status) {
        return orderRepository.findByStatusOrderByCreatedAtDesc(status);
    }

    public List<Order> findByClient(Long clientId) {
        return orderRepository.findByClientIdOrderByCreatedAtDesc(clientId);
    }

    /** Статусы, при которых заказ считается завершённым и подчиняется обычному фильтру по дате. */
    private static final List<OrderStatus> FINAL_STATUSES = List.of(OrderStatus.COMPLETED, OrderStatus.CANCELLED);

    /**
     * Заказы, созданные в диапазоне дат [from, to] включительно — для фильтра по дате в
     * приложении. Незавершённые заказы (см. FINAL_STATUSES) возвращаются всегда, даже если их
     * дата создания вне диапазона — см. комментарий к OrderRepository.findForDateRange.
     */
    public List<Order> findByDateRange(LocalDate from, LocalDate to) {
        return orderRepository.findForDateRange(null, from.atStartOfDay(), to.plusDays(1).atStartOfDay(), FINAL_STATUSES);
    }

    public List<Order> findByStatusAndDateRange(OrderStatus status, LocalDate from, LocalDate to) {
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
        order.setStatus(OrderStatus.NEW);
        order.setFromWhatsapp(form.isFromWhatsapp());

        for (OrderForm.OrderItemForm itemForm : form.getItems()) {
            if (itemForm.getProductId() == null || itemForm.getQuantity() == null) {
                continue;
            }
            Product product = productRepository.findById(itemForm.getProductId())
                    .orElseThrow(() -> new IllegalArgumentException("Товар не найден: " + itemForm.getProductId()));
            OrderItem item = new OrderItem();
            item.setProduct(product);
            item.setQuantity(itemForm.getQuantity());
            item.setPrice(clientPriceService.resolvePrice(client, product));
            order.addItem(item);
        }

        if (order.getItems().isEmpty()) {
            throw new IllegalArgumentException("В заказе должна быть хотя бы одна позиция");
        }

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
        oneCOrderSyncService.trySyncOrder(order);
        return order;
    }

    /** Сборка завершена: готовая продукция всегда в наличии, поэтому заказ сразу готов к отгрузке. */
    @Transactional
    public Order confirm(Long orderId) {
        Order order = findById(orderId);
        if (order.getStatus() != OrderStatus.NEW) {
            throw new IllegalStateException("Отметить собранным можно только заказ в процессе сборки");
        }
        order.setStatus(OrderStatus.READY);
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
     * эффектов кроме смены статуса и уведомлений (не списывает остатки, не создаёт
     * ProductionTask, не синхронизирует с 1С — это происходит только в ship()/updateItems()),
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
        return order;
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
        Order order = findById(orderId);
        if (order.getStatus() == OrderStatus.DELIVERED || order.getStatus() == OrderStatus.COMPLETED
                || order.getStatus() == OrderStatus.CANCELLED) {
            throw new IllegalStateException("Состав заказа в этом статусе изменить нельзя");
        }
        for (OrderItemUpdateRequest.ItemUpdate update : updates) {
            if (update.itemId() == null) {
                if (update.productId() == null || update.quantity().signum() <= 0) {
                    continue;
                }
                Product product = productRepository.findById(update.productId())
                        .orElseThrow(() -> new IllegalArgumentException("Товар не найден: " + update.productId()));
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
                Product product = productRepository.findById(update.productId())
                        .orElseThrow(() -> new IllegalArgumentException("Товар не найден: " + update.productId()));
                item.setProduct(product);
                item.setPrice(clientPriceService.resolvePrice(order.getClient(), product));
            }
            item.setQuantity(update.quantity());
        }
        if (order.getItems().isEmpty()) {
            throw new IllegalArgumentException("В заказе должна остаться хотя бы одна позиция");
        }
        order = orderRepository.save(order);
        oneCOrderSyncService.trySyncOrder(order);
        return order;
    }

    /** Если заказ уже был выгружен в 1С — соответствующий документ помечается там на удаление (см. OneCOrderSyncService.trySyncCancel). */
    @Transactional
    public Order cancel(Long orderId) {
        Order order = findById(orderId);
        if (order.getStatus() == OrderStatus.DELIVERED || order.getStatus() == OrderStatus.COMPLETED) {
            throw new IllegalStateException("Отгруженный заказ нельзя отменить");
        }
        order.setStatus(OrderStatus.CANCELLED);
        notificationService.notify(recipientOf(order), "Заказ #" + order.getId() + " отменён", order);
        String cancelMessage = "Заказ №" + order.getId() + " (" + order.getClient().getName() + ") отменён";
        notificationService.notifyRole(Role.ACCOUNTANT, cancelMessage, order);
        notificationService.notifyRole(Role.ADMIN, cancelMessage, order);
        oneCOrderSyncService.trySyncCancel(order);
        return order;
    }

    /** Отгрузка складом ГП: фиксирует гос. номер ТС и переводит заказ в статус "отгружен". */
    @Transactional
    public Order ship(Long orderId, String vehicleNumber, String shippedByName) {
        Order order = findById(orderId);
        if (order.getStatus() != OrderStatus.READY) {
            throw new IllegalStateException("Отгрузить можно только заказ, готовый к отгрузке");
        }
        if (vehicleNumber == null || vehicleNumber.isBlank()) {
            throw new IllegalArgumentException("Укажите гос. номер ТС");
        }
        order.setStatus(OrderStatus.DELIVERED);
        order.setVehicleNumber(vehicleNumber.trim());
        order.setShippedAt(LocalDateTime.now());
        order.setShippedByName(shippedByName);
        for (OrderItem item : order.getItems()) {
            Product product = item.getProduct();
            product.setQuantityInStock(product.getQuantityInStock().subtract(item.getQuantity()));
        }
        String message = "Заказ " + order.getClient().getName() + " " + order.getVehicleNumber() + " отгружен";
        notificationService.notifyRole(Role.ACCOUNTANT, message, order);
        notificationService.notifyRole(Role.ADMIN, message, order);
        sendInvoiceToClient(order);
        // Документ в 1С уже существует непроведённым черновиком (создан при оформлении заказа —
        // с датой создания заказа), но дата документа должна быть датой ФАКТИЧЕСКОЙ отгрузки —
        // shippedAt выше уже проставлен, поэтому trySyncOrder здесь перезапишет дату на неё
        // (см. OneCOrderSyncService class-javadoc). Проведение документа в 1С теперь только
        // вручную в самой 1С — автопроведение через OData не формировало движений по складу
        // (флаг Posted выставлялся, товар физически не списывался, требовалось повторное ручное
        // проведение), поэтому оно убрано целиком (см. историю, tryPostOrder/postEligibleOrders).
        oneCOrderSyncService.trySyncOrder(order);
        return order;
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
            String caption = "Накладная № " + order.getId() + ". Гос. номер ТС: " + order.getVehicleNumber();
            whatsAppGatewayClient.sendDocument(phone, pdf, "nakladnaya_" + order.getId() + ".pdf", caption);
        } catch (Exception e) {
            log.warn("Не удалось отправить накладную по заказу №{} клиенту в WhatsApp: {}", order.getId(), e.getMessage());
        }
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
        return order;
    }

    public List<Order> findUnpaidDebts() {
        return orderRepository.findUnpaidDebts();
    }

    public List<Order> findOverdueDebts() {
        return orderRepository.findOverdueDebts(LocalDate.now());
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
