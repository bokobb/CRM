package com.confectionery.crm.service;

import com.confectionery.crm.domain.Ingredient;
import com.confectionery.crm.domain.Product;
import com.confectionery.crm.domain.Recipe;
import com.confectionery.crm.domain.RecipeItem;
import com.confectionery.crm.domain.StockMovement;
import com.confectionery.crm.domain.StockMovementType;
import com.confectionery.crm.repository.IngredientRepository;
import com.confectionery.crm.repository.ProductRepository;
import com.confectionery.crm.repository.RecipeRepository;
import com.confectionery.crm.repository.StockMovementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;

/**
 * Списание сырья со склада по рецептуре товара (автоматически, при завершении цеховой
 * задачи — см. ProductionTaskService) плюс ручные поступления/списания с журналом
 * (StockMovement), которые ведёт зав. складом через раздел "Склад" в приложении.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockService {

    private final RecipeRepository recipeRepository;
    private final IngredientRepository ingredientRepository;
    private final ProductRepository productRepository;
    private final StockMovementRepository stockMovementRepository;
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

    @Transactional
    public StockMovement receive(Ingredient ingredient, BigDecimal quantity, String reason, String by) {
        requirePositive(quantity, "поступления");
        ingredient.setQuantityInStock(ingredient.getQuantityInStock().add(quantity));
        ingredientRepository.save(ingredient);
        return logMovement(ingredient, null, StockMovementType.RECEIPT, quantity, reason, by);
    }

    @Transactional
    public StockMovement writeOff(Ingredient ingredient, BigDecimal quantity, String reason, String by) {
        requirePositive(quantity, "списания");
        if (ingredient.getQuantityInStock().compareTo(quantity) < 0) {
            throw new IllegalArgumentException("На складе меньше, чем указано для списания");
        }
        ingredient.setQuantityInStock(ingredient.getQuantityInStock().subtract(quantity));
        ingredientRepository.save(ingredient);
        return logMovement(ingredient, null, StockMovementType.WRITE_OFF, quantity, reason, by);
    }

    /** То же самое, но для готовой продукции (см. также автосписание при отгрузке — OrderService.ship). */
    @Transactional
    public StockMovement receiveProduct(Product product, BigDecimal quantity, String reason, String by) {
        requirePositive(quantity, "поступления");
        product.setQuantityInStock(product.getQuantityInStock().add(quantity));
        productRepository.save(product);
        return logMovement(null, product, StockMovementType.RECEIPT, quantity, reason, by);
    }

    @Transactional
    public StockMovement writeOffProduct(Product product, BigDecimal quantity, String reason, String by) {
        requirePositive(quantity, "списания");
        if (product.getQuantityInStock().compareTo(quantity) < 0) {
            throw new IllegalArgumentException("На складе меньше, чем указано для списания");
        }
        product.setQuantityInStock(product.getQuantityInStock().subtract(quantity));
        productRepository.save(product);
        return logMovement(null, product, StockMovementType.WRITE_OFF, quantity, reason, by);
    }

    private void requirePositive(BigDecimal quantity, String action) {
        if (quantity == null || quantity.signum() <= 0) {
            throw new IllegalArgumentException("Количество " + action + " должно быть больше нуля");
        }
    }

    private StockMovement logMovement(Ingredient ingredient, Product product, StockMovementType type,
                                       BigDecimal quantity, String reason, String by) {
        StockMovement movement = new StockMovement();
        movement.setIngredient(ingredient);
        movement.setProduct(product);
        movement.setType(type);
        movement.setQuantity(quantity);
        movement.setReason(reason);
        movement.setCreatedBy(by);
        return stockMovementRepository.save(movement);
    }

    public List<StockMovement> findMovements(StockMovementType type) {
        return type == null ? stockMovementRepository.findAllByOrderByCreatedAtDesc()
                : stockMovementRepository.findByTypeOrderByCreatedAtDesc(type);
    }
}
