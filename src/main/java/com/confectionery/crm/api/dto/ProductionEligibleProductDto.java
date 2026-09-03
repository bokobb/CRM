package com.confectionery.crm.api.dto;

import com.confectionery.crm.domain.Recipe;

import java.math.BigDecimal;

/**
 * Товар, у которого есть рецептура — для выбора при создании заявки на сырьё. yieldQuantity
 * нужен клиенту, чтобы пересчитывать замесы <-> штуки на лету, не спрашивая сервер на каждое нажатие.
 */
public record ProductionEligibleProductDto(Long id, String name, String unit, String group, BigDecimal yieldQuantity) {
    public static ProductionEligibleProductDto from(Recipe recipe) {
        return new ProductionEligibleProductDto(
                recipe.getProduct().getId(), recipe.getProduct().getName(), recipe.getProduct().getUnit(),
                recipe.getProduct().getGroup(), recipe.getYieldQuantity());
    }
}
