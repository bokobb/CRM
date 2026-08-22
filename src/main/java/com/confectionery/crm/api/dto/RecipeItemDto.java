package com.confectionery.crm.api.dto;

import com.confectionery.crm.domain.RecipeItem;

import java.math.BigDecimal;

public record RecipeItemDto(
        Long ingredientId,
        String ingredientName,
        String unit,
        BigDecimal quantity
) {
    public static RecipeItemDto from(RecipeItem item) {
        return new RecipeItemDto(
                item.getIngredient().getId(),
                item.getIngredient().getName(),
                item.getIngredient().getUnit(),
                item.getQuantity()
        );
    }
}
