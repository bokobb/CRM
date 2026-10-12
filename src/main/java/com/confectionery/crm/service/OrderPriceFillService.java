package com.confectionery.crm.service;

import com.confectionery.crm.domain.Order;
import com.confectionery.crm.domain.OrderItem;
import com.confectionery.crm.domain.OrderStatus;
import com.confectionery.crm.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Set;

/**
 * Цена позиции фиксируется при создании заказа (ClientPriceService.resolvePrice). Если у товара тогда
 * не было ни индивидуальной, ни базовой цены, в заказе остаётся 0. Здесь такие нули заполняются по
 * текущему прайсу: сами — когда сохраняют цену клиента или товара, и кнопкой «Подтянуть цены» в заказе.
 * Только неотгруженные заказы: отгруженные и отменённые не трогаем. Ненулевые цены не меняются.
 */
@Service
@RequiredArgsConstructor
public class OrderPriceFillService {

    static final Set<OrderStatus> OPEN = EnumSet.of(OrderStatus.NEW, OrderStatus.CONFIRMED,
            OrderStatus.IN_PRODUCTION, OrderStatus.READY);

    private final OrderRepository orderRepository;
    private final ClientPriceService clientPriceService;
    private final OneCQueue oneCQueue;

    /** Заполнить нули в одном заказе; возвращает число исправленных позиций. */
    @Transactional
    public int fill(Long orderId) {
        Order order = orderRepository.findById(orderId).orElseThrow(() -> new IllegalArgumentException("Заказ не найден"));
        if (!OPEN.contains(order.getStatus())) {
            throw new IllegalStateException("Цены подтягиваются только в неотгруженном заказе");
        }
        return fillOrder(order, null);
    }

    /**
     * После сохранения цены: clientId — только заказы этого клиента (индивидуальная цена),
     * null — у всех клиентов (базовая цена товара; у кого есть своя цена, её и возьмёт resolvePrice).
     */
    @Transactional
    public int afterPriceChange(Long clientId, Long productId) {
        int fixed = 0;
        for (Order order : clientId == null ? orderRepository.findAllByOrderByCreatedAtDesc()
                : orderRepository.findByClientIdOrderByCreatedAtDesc(clientId)) {
            if (OPEN.contains(order.getStatus())) fixed += fillOrder(order, productId);
        }
        return fixed;
    }

    private int fillOrder(Order order, Long onlyProductId) {
        int fixed = 0;
        for (OrderItem item : order.getItems()) {
            if (item.getPrice() != null && item.getPrice().signum() != 0) continue;
            if (onlyProductId != null && !onlyProductId.equals(item.getProduct().getId())) continue;
            BigDecimal price = clientPriceService.resolvePrice(order.getClient(), item.getProduct());
            if (price != null && price.signum() > 0) {
                item.setPrice(price);
                fixed++;
            }
        }
        if (fixed > 0) {
            orderRepository.save(order);
            oneCQueue.order(order);   // в 1С «Реализация» уйдёт с новыми ценами
        }
        return fixed;
    }
}
