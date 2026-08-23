package com.confectionery.crm.api.dto;

import com.confectionery.crm.domain.StockMovement;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record StockMovementDto(
        Long id,
        /** "INGREDIENT" (сырьё) или "PRODUCT" (готовая продукция) — какое из двух ниже заполнено. */
        String kind,
        Long itemId,
        String itemName,
        String unit,
        String type,
        String typeLabel,
        BigDecimal quantity,
        String reason,
        String createdBy,
        LocalDateTime createdAt
) {
    public static StockMovementDto from(StockMovement m) {
        boolean isProduct = m.getProduct() != null;
        return new StockMovementDto(
                m.getId(),
                isProduct ? "PRODUCT" : "INGREDIENT",
                isProduct ? m.getProduct().getId() : m.getIngredient().getId(),
                isProduct ? m.getProduct().getName() : m.getIngredient().getName(),
                isProduct ? m.getProduct().getUnit() : m.getIngredient().getUnit(),
                m.getType().name(),
                m.getType().getLabel(),
                m.getQuantity(),
                m.getReason(),
                m.getCreatedBy(),
                m.getCreatedAt()
        );
    }
}
