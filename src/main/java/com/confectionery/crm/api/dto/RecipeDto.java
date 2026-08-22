package com.confectionery.crm.api.dto;

import com.confectionery.crm.domain.Recipe;

import java.math.BigDecimal;
import java.util.List;

public record RecipeDto(
        BigDecimal yieldQuantity,
        List<RecipeItemDto> items
) {
    public static RecipeDto from(Recipe recipe) {
        if (recipe == null) {
            return new RecipeDto(BigDecimal.ONE, List.of());
        }
        return new RecipeDto(
                recipe.getYieldQuantity(),
                recipe.getItems().stream().map(RecipeItemDto::from).toList()
        );
    }
}
