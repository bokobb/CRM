package com.confectionery.crm.service;

import com.confectionery.crm.domain.Ingredient;
import com.confectionery.crm.domain.Product;
import com.confectionery.crm.domain.ProductAlias;
import com.confectionery.crm.domain.Recipe;
import com.confectionery.crm.domain.RecipeItem;
import com.confectionery.crm.repository.IngredientRepository;
import com.confectionery.crm.repository.ProductAliasRepository;
import com.confectionery.crm.repository.ProductRepository;
import com.confectionery.crm.repository.RecipeRepository;
import com.confectionery.crm.web.dto.RecipeForm;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final RecipeRepository recipeRepository;
    private final IngredientRepository ingredientRepository;
    private final ProductAliasRepository productAliasRepository;

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

    /**
     * Синонимы (как клиенты называют товар в WhatsApp), сгруппированные по товару — см.
     * ClaudeOrderParserService, подмешивается в промпт распознавания заказов. Пополняется
     * автоматически, когда администратор вручную сопоставляет нераспознанную позицию
     * (см. WhatsAppOrderIntakeService.resolvePending) — так система "запоминает" сленг клиентов.
     */
    public Map<Long, List<String>> aliasesGroupedByProduct() {
        return productAliasRepository.findAll().stream()
                .collect(Collectors.groupingBy(a -> a.getProduct().getId(),
                        Collectors.mapping(ProductAlias::getAlias, Collectors.toList())));
    }

    public ProductAlias saveAliasIfNew(Product product, String rawText) {
        String normalized = rawText == null ? "" : rawText.trim();
        if (normalized.isEmpty()) return null;
        boolean exists = productAliasRepository.findByProductId(product.getId()).stream()
                .anyMatch(a -> a.getAlias().equalsIgnoreCase(normalized));
        if (exists) return null;
        ProductAlias alias = new ProductAlias();
        alias.setProduct(product);
        alias.setAlias(normalized);
        return productAliasRepository.save(alias);
    }
}
