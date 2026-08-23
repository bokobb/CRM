package com.confectionery.crm.api.dto;

import com.confectionery.crm.domain.ClientPrice;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ClientPriceDto(
        Long id,
        Long productId,
        String productName,
        BigDecimal basePrice,
        BigDecimal price,
        LocalDateTime updatedAt
) {
    public static ClientPriceDto from(ClientPrice cp) {
        return new ClientPriceDto(cp.getId(), cp.getProduct().getId(), cp.getProduct().getName(),
                cp.getProduct().getPrice(), cp.getPrice(), cp.getUpdatedAt());
    }
}
