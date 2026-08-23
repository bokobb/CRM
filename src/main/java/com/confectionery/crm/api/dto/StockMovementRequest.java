package com.confectionery.crm.api.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/** Ровно одно из ingredientId/productId должно быть заполнено — проверяется в контроллере. */
public record StockMovementRequest(
        Long ingredientId,
        Long productId,
        @NotNull BigDecimal quantity,
        String reason
) {
}
