package com.confectionery.crm.api.dto;

import com.confectionery.crm.domain.OrderItem;

import java.math.BigDecimal;

public record OrderItemDto(
        Long id,
        Long productId,
        String productName,
        BigDecimal quantity,
        BigDecimal price
) {
    public static OrderItemDto from(OrderItem item) {
        return new OrderItemDto(item.getId(), item.getProduct().getId(), item.getProduct().getName(),
                item.getQuantity(), item.getPrice());
    }
}
