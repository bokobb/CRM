package com.confectionery.crm.service;

import com.confectionery.crm.domain.Ingredient;
import com.confectionery.crm.domain.Product;
import com.confectionery.crm.domain.ProductionRequest;
import com.confectionery.crm.domain.ProductionRequestItem;
import com.confectionery.crm.domain.Recipe;
import com.confectionery.crm.domain.RecipeItem;
import com.confectionery.crm.repository.IngredientRepository;
import com.confectionery.crm.repository.ProductRepository;
import com.confectionery.crm.repository.RecipeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Правка позиций заявки: «Применить поправки» — рецептура на новые замесы + поправки; «Очистить» — только рецептура. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:request-adjustments;DB_CLOSE_DELAY=-1",
        "crm.onec.base-url=",
        "spring.task.scheduling.enabled=false"
})
class ProductionRequestAdjustmentsTest {

    @Autowired ProductionRequestService requestService;
    @Autowired IngredientRepository ingredientRepository;
    @Autowired ProductRepository productRepository;
    @Autowired RecipeRepository recipeRepository;
    @Autowired TransactionTemplate tx;

    @Test
    void keepAndClearAdjustments() {
        String s = String.valueOf(System.nanoTime());
        Ingredient flour = ingredient("Мука " + s, "кг");
        Ingredient sugar = ingredient("Сахар " + s, "кг");
        Ingredient butter = ingredient("Масло " + s, "кг");
        Ingredient margarine = ingredient("Маргарин " + s, "кг");
        Ingredient gloves = ingredient("Перчатки " + s, "шт");
        Product product = product("Коржик " + s, Map.of(flour, "10", sugar, "2", butter, "1"));

        ProductionRequest keep = withAdjustments(product, sugar, butter, margarine, gloves);
        requestService.update(keep.getId(), List.of(new ProductionRequestService.Line(product.getId(), new BigDecimal("20"))),
                "test", null, true, true);
        assertThat(items(keep.getId())).isEqualTo(Map.of(
                flour.getName(), "200", sugar.getName(), "46", margarine.getName() + " вместо " + butter.getName(), "20",
                gloves.getName(), "10"));

        ProductionRequest clear = withAdjustments(product, sugar, butter, margarine, gloves);
        assertThat(requestService.adjustments(clear.getId())).hasSize(3);
        requestService.update(clear.getId(), List.of(new ProductionRequestService.Line(product.getId(), new BigDecimal("20"))),
                "test", null, true, false);
        assertThat(items(clear.getId())).isEqualTo(Map.of(
                flour.getName(), "200", sugar.getName(), "40", butter.getName(), "20"));
        assertThat(requestService.adjustments(clear.getId())).isEmpty();
    }

    /** 24,5 замеса + перчатки 10 шт, сахар +6 кг, масло заменено маргарином. */
    private ProductionRequest withAdjustments(Product product, Ingredient sugar, Ingredient butter, Ingredient margarine,
                                              Ingredient gloves) {
        ProductionRequest r = requestService.create("Коржики",
                List.of(new ProductionRequestService.Line(product.getId(), new BigDecimal("24.5"))), "test", LocalDate.now());
        requestService.addItem(r.getId(), gloves.getId(), new BigDecimal("10"), "test");
        requestService.substituteItem(r.getId(), itemId(r.getId(), sugar), null, new BigDecimal("55"), "test");
        requestService.substituteItem(r.getId(), itemId(r.getId(), butter), margarine.getId(), null, "test");
        return r;
    }

    private Long itemId(Long requestId, Ingredient ingredient) {
        return tx.execute(st -> requestService.findById(requestId).getItems().stream()
                .filter(i -> i.getIngredient().getId().equals(ingredient.getId())).findFirst().orElseThrow().getId());
    }

    private Map<String, String> items(Long requestId) {
        return tx.execute(st -> requestService.findById(requestId).getItems().stream().collect(Collectors.toMap(
                (ProductionRequestItem i) -> i.getIngredient().getName()
                        + (i.getOriginalIngredientName() != null ? " вместо " + i.getOriginalIngredientName() : ""),
                i -> i.getRequiredQuantity().stripTrailingZeros().toPlainString())));
    }

    private Ingredient ingredient(String name, String unit) {
        Ingredient i = new Ingredient();
        i.setName(name);
        i.setUnit(unit);
        return ingredientRepository.save(i);
    }

    private Product product(String name, Map<Ingredient, String> perBatch) {
        Product p = new Product();
        p.setName(name);
        p.setUnit("шт");
        p = productRepository.save(p);
        Recipe recipe = new Recipe();
        recipe.setProduct(p);
        perBatch.forEach((ingredient, qty) -> {
            RecipeItem item = new RecipeItem();
            item.setIngredient(ingredient);
            item.setQuantity(new BigDecimal(qty));
            recipe.addItem(item);
        });
        recipeRepository.save(recipe);
        return p;
    }
}
