package com.confectionery.crm.api.dto;

import com.confectionery.crm.domain.WhatsAppInboundItem;

import java.math.BigDecimal;

public record WhatsAppInboundItemDto(
        Long id,
        String rawProductText,
        BigDecimal quantity,
        Long productId,
        String productName
) {
    public static WhatsAppInboundItemDto from(WhatsAppInboundItem i) {
        return new WhatsAppInboundItemDto(
                i.getId(), i.getRawProductText(), i.getQuantity(),
                i.getProduct() != null ? i.getProduct().getId() : null,
                i.getProduct() != null ? i.getProduct().getName() : null
        );
    }
}
