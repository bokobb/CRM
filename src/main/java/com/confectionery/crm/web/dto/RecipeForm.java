package com.confectionery.crm.web.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class RecipeForm {

    private BigDecimal yieldQuantity = BigDecimal.ONE;

    private List<RecipeItemForm> items = new ArrayList<>();

    public BigDecimal getYieldQuantity() {
        return yieldQuantity;
    }

    public void setYieldQuantity(BigDecimal yieldQuantity) {
        this.yieldQuantity = yieldQuantity;
    }

    public List<RecipeItemForm> getItems() {
        return items;
    }

    public void setItems(List<RecipeItemForm> items) {
        this.items = items;
    }

    public static class RecipeItemForm {
        private Long ingredientId;
        private BigDecimal quantity;

        public Long getIngredientId() {
            return ingredientId;
        }

        public void setIngredientId(Long ingredientId) {
            this.ingredientId = ingredientId;
        }

        public BigDecimal getQuantity() {
            return quantity;
        }

        public void setQuantity(BigDecimal quantity) {
            this.quantity = quantity;
        }
    }
}
