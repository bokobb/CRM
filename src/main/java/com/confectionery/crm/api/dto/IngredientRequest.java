package com.confectionery.crm.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record IngredientRequest(
        @NotBlank String name,
        @NotBlank String unit,
        @NotNull BigDecimal quantityInStock,
        @NotNull BigDecimal minThreshold
) {
}
