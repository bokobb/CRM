package com.confectionery.crm.service;

import com.confectionery.crm.domain.Ingredient;
import com.confectionery.crm.domain.Product;
import com.confectionery.crm.domain.Recipe;
import com.confectionery.crm.domain.RecipeItem;
import com.confectionery.crm.repository.IngredientRepository;
import com.confectionery.crm.repository.ProductRepository;
import com.confectionery.crm.repository.RecipeRepository;
import com.confectionery.crm.web.dto.RecipeForm;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final RecipeRepository recipeRepository;
    private final IngredientRepository ingredientRepository;

    public List<Product> findAll() {
        return productRepository.findAll();
    }

    public Product findById(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Товар не найден: " + id));
    }

    public Product save(Product product) {
        return productRepository.save(product);
    }

    public void delete(Long id) {
        productRepository.deleteById(id);
    }

    /** Полностью заменяет рецептуру товара данными из формы. */
    public void saveRecipe(Long productId, RecipeForm form) {
        Product product = findById(productId);
        Recipe recipe = recipeRepository.findByProductId(productId).orElseGet(Recipe::new);
        recipe.setProduct(product);
        recipe.setYieldQuantity(form.getYieldQuantity() == null ? BigDecimal.ONE : form.getYieldQuantity());
        recipe.getItems().clear();

        for (RecipeForm.RecipeItemForm itemForm : form.getItems()) {
            if (itemForm.getIngredientId() == null || itemForm.getQuantity() == null) {
                continue;
            }
            Ingredient ingredient = ingredientRepository.findById(itemForm.getIngredientId())
                    .orElseThrow(() -> new IllegalArgumentException("Ингредиент не найден: " + itemForm.getIngredientId()));
            RecipeItem item = new RecipeItem();
            item.setIngredient(ingredient);
            item.setQuantity(itemForm.getQuantity());
            recipe.addItem(item);
        }

        recipeRepository.save(recipe);
    }

    public Recipe findRecipe(Long productId) {
        return recipeRepository.findByProductId(productId).orElse(null);
    }
}
