package com.confectionery.crm.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PurchaseRequest(
        @NotNull Long ingredientId,
        @NotNull BigDecimal quantity,
        @NotBlank String supplier,
        BigDecimal totalPrice,
        LocalDate purchaseDate
) {
}
