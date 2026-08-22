package com.confectionery.crm.api.dto;

import java.math.BigDecimal;
import java.util.List;

public record RecipeRequest(
        BigDecimal yieldQuantity,
        List<RecipeItemRequest> items
) {
    public record RecipeItemRequest(Long ingredientId, BigDecimal quantity) {
    }
}
