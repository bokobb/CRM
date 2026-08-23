package com.confectionery.crm.service;

import com.confectionery.crm.domain.*;
import com.confectionery.crm.repository.ClientRepository;
import com.confectionery.crm.repository.OrderRepository;
import com.confectionery.crm.repository.ProductRepository;
import com.confectionery.crm.web.dto.OrderForm;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public List<Order> findAll() {
        return orderRepository.findAllByOrderByCreatedAtDesc();
    }

    public List<Order> findByStatus(OrderStatus status) {
        return orderRepository.findByStatusOrderByCreatedAtDesc(status);
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

        return orderRepository.save(order);
    }

    /** Подтверждение заказа: готовая продукция всегда в наличии, поэтому заказ сразу готов к отгрузке. */
    @Transactional
    public Order confirm(Long orderId) {
        Order order = findById(orderId);
        if (order.getStatus() != OrderStatus.NEW) {
            throw new IllegalStateException("Подтвердить можно только новый заказ");
        }
        order.setStatus(OrderStatus.READY);
        notificationService.notify(recipientOf(order), "Заказ #" + order.getId() + " подтверждён, готов к отгрузке", order);
        return order;
    }

    @Transactional
    public Order cancel(Long orderId) {
        Order order = findById(orderId);
        if (order.getStatus() == OrderStatus.DELIVERED) {
            throw new IllegalStateException("Отгруженный заказ нельзя отменить");
        }
        order.setStatus(OrderStatus.CANCELLED);
        notificationService.notify(recipientOf(order), "Заказ #" + order.getId() + " отменён", order);
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
        notificationService.notify(recipientOf(order),
                "Заказ #" + order.getId() + " отгружен, ТС " + order.getVehicleNumber(), order);
        return order;
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
