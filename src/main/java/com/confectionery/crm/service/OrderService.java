package com.confectionery.crm.service;

import com.confectionery.crm.domain.*;
import com.confectionery.crm.repository.ClientRepository;
import com.confectionery.crm.repository.OrderRepository;
import com.confectionery.crm.repository.ProductRepository;
import com.confectionery.crm.repository.ProductionTaskRepository;
import com.confectionery.crm.web.dto.OrderForm;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Управляет жизненным циклом заказа и автоматизацией, привязанной к смене статуса:
 * подтверждение заказа -> генерация цеховых задач + уведомление клиента.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final ClientRepository clientRepository;
    private final ProductRepository productRepository;
    private final ProductionTaskRepository productionTaskRepository;
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
            item.setPrice(product.getPrice());
            order.addItem(item);
        }

        if (order.getItems().isEmpty()) {
            throw new IllegalArgumentException("В заказе должна быть хотя бы одна позиция");
        }

        return orderRepository.save(order);
    }

    /** Подтверждение заказа: автоматически создаёт задачи цеху и уведомляет клиента. */
    @Transactional
    public Order confirm(Long orderId) {
        Order order = findById(orderId);
        if (order.getStatus() != OrderStatus.NEW) {
            throw new IllegalStateException("Подтвердить можно только новый заказ");
        }
        order.setStatus(OrderStatus.CONFIRMED);

        for (OrderItem item : order.getItems()) {
            ProductionTask task = new ProductionTask();
            task.setOrder(order);
            task.setProduct(item.getProduct());
            task.setQuantity(item.getQuantity());
            task.setStatus(ProductionTaskStatus.PENDING);
            productionTaskRepository.save(task);
        }
        order.setStatus(OrderStatus.IN_PRODUCTION);

        notificationService.notify(recipientOf(order), "Заказ #" + order.getId() + " принят и передан в производство", order);
        return order;
    }

    @Transactional
    public Order cancel(Long orderId) {
        Order order = findById(orderId);
        if (order.getStatus() == OrderStatus.DELIVERED) {
            throw new IllegalStateException("Выданный заказ нельзя отменить");
        }
        order.setStatus(OrderStatus.CANCELLED);
        notificationService.notify(recipientOf(order), "Заказ #" + order.getId() + " отменён", order);
        return order;
    }

    @Transactional
    public Order markDelivered(Long orderId) {
        Order order = findById(orderId);
        if (order.getStatus() != OrderStatus.READY) {
            throw new IllegalStateException("Выдать можно только готовый заказ");
        }
        order.setStatus(OrderStatus.DELIVERED);
        notificationService.notify(recipientOf(order), "Заказ #" + order.getId() + " выдан клиенту", order);
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
