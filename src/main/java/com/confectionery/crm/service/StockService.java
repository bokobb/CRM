package com.confectionery.crm.service;

import com.confectionery.crm.domain.Ingredient;
import com.confectionery.crm.domain.Product;
import com.confectionery.crm.domain.Recipe;
import com.confectionery.crm.domain.RecipeItem;
import com.confectionery.crm.repository.IngredientRepository;
import com.confectionery.crm.repository.RecipeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;

/**
 * Списание сырья со склада по рецептуре товара. Вызывается автоматически, когда
 * цеховая задача (ProductionTask) переводится в статус DONE — см. ProductionTaskService.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockService {

    private final RecipeRepository recipeRepository;
    private final IngredientRepository ingredientRepository;
    private final NotificationService notificationService;

    public void deductForProduction(Product product, BigDecimal producedQuantity) {
        recipeRepository.findByProductId(product.getId()).ifPresentOrElse(
                recipe -> deduct(recipe, producedQuantity),
                () -> log.info("У товара '{}' нет рецептуры — списание сырья пропущено", product.getName())
        );
    }

    private void deduct(Recipe recipe, BigDecimal producedQuantity) {
        BigDecimal ratio = producedQuantity.divide(recipe.getYieldQuantity(), MathContext.DECIMAL64);

        for (RecipeItem item : recipe.getItems()) {
            Ingredient ingredient = item.getIngredient();
            BigDecimal toDeduct = item.getQuantity().multiply(ratio);
            BigDecimal updated = ingredient.getQuantityInStock().subtract(toDeduct);
            ingredient.setQuantityInStock(updated);
            ingredientRepository.save(ingredient);

            if (ingredient.isLowStock()) {
                String prefix = "Низкий остаток: " + ingredient.getName();
                if (!notificationService.wasNotifiedRecently("склад", prefix)) {
                    notificationService.notify("склад", prefix + " — остаток " + updated + " " + ingredient.getUnit()
                            + ", минимум " + ingredient.getMinThreshold() + " " + ingredient.getUnit(), null);
                }
            }
        }
    }
}
