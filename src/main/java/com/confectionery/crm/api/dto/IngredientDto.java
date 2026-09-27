package com.confectionery.crm.api.dto;

import com.confectionery.crm.domain.Ingredient;

import java.math.BigDecimal;

public record IngredientDto(
        Long id,
        String name,
        String unit,
        BigDecimal quantityInStock,
        BigDecimal minThreshold,
        boolean lowStock,
        boolean active
) {
    public static IngredientDto from(Ingredient i) {
        return new IngredientDto(i.getId(), i.getName(), i.getUnit(), i.getQuantityInStock(), i.getMinThreshold(), i.isLowStock(), i.isActive());
    }
}
