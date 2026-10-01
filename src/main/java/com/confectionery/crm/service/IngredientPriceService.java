package com.confectionery.crm.service;

import com.confectionery.crm.domain.Ingredient;
import com.confectionery.crm.domain.PurchaseItem;
import com.confectionery.crm.domain.Recipe;
import com.confectionery.crm.domain.RecipeItem;
import com.confectionery.crm.repository.PurchaseRepository;
import com.confectionery.crm.repository.RecipeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * Цена единицы сырья для себестоимости (техкарты, экран «Производство»).
 * <ul>
 *   <li>Обычное сырьё — последняя закупка с суммой не позже даты {@code asOf}; если до этой даты
 *   закупок не было — самая ранняя из более поздних (лучше, чем ничего).</li>
 *   <li>Полуфабрикат («Пряник», «Чак-чак»…) зеркалится в сырьё с тем же названием (см.
 *   StockService.receiveProductForProduction) и не закупается — его цена считается по рецептуре
 *   товара-полуфабриката: Σ(сырьё × цена) / выход замеса.</li>
 * </ul>
 * Сырьё без закупок и без рецептуры в результат не попадает — вызывающий показывает его как «нет цены».
 */
@Service
@RequiredArgsConstructor
public class IngredientPriceService {

    public enum Source { PURCHASE, SEMI_FINISHED }

    public record Price(BigDecimal unitPrice, LocalDate date, Source source) {
    }

    private final PurchaseRepository purchaseRepository;
    private final RecipeRepository recipeRepository;

    /** Калькулятор на одну дату: закупки и рецептуры полуфабрикатов читаются один раз. */
    @Transactional(readOnly = true)
    public Calculator calculator(LocalDate asOf) {
        Map<String, Recipe> semiRecipes = new HashMap<>();
        for (Recipe r : recipeRepository.findAll()) {
            if (r.getProduct().isSemiFinished()) {
                semiRecipes.put(key(r.getProduct().getName()), r);
            }
        }
        return new Calculator(asOf, semiRecipes);
    }

    private static String key(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    public class Calculator {
        private final LocalDate asOf;
        private final Map<String, Recipe> semiRecipes;
        private final Map<Long, Optional<Price>> cache = new HashMap<>();
        private final Set<Long> inProgress = new HashSet<>();
        private final Set<Long> checked = new HashSet<>();

        private Calculator(LocalDate asOf, Map<String, Recipe> semiRecipes) {
            this.asOf = asOf;
            this.semiRecipes = semiRecipes;
        }

        /** Цены сразу пачкой — один запрос к закупкам на все виды сырья. */
        public Map<Long, Price> prices(Collection<Ingredient> ingredients) {
            loadPurchases(ingredients.stream().map(Ingredient::getId).toList());
            Map<Long, Price> out = new HashMap<>();
            for (Ingredient i : ingredients) {
                price(i).ifPresent(p -> out.put(i.getId(), p));
            }
            return out;
        }

        public Optional<Price> price(Ingredient ingredient) {
            loadPurchases(List.of(ingredient.getId()));
            Optional<Price> cached = cache.get(ingredient.getId());
            if (cached != null) return cached;
            Optional<Price> semi = semiFinishedPrice(ingredient);
            if (!inProgress.contains(ingredient.getId())) cache.put(ingredient.getId(), semi);
            return semi;
        }

        /** Цены из закупок; сырьё без закупок остаётся вне кеша (его цену даст рецептура полуфабриката). */
        private void loadPurchases(List<Long> ids) {
            List<Long> missing = ids.stream().filter(id -> !cache.containsKey(id) && !checked.contains(id)).distinct().toList();
            if (missing.isEmpty()) return;
            checked.addAll(missing);
            Map<Long, PurchaseItem> before = new HashMap<>();
            Map<Long, PurchaseItem> after = new HashMap<>();
            for (PurchaseItem pi : purchaseRepository.findPricedItemsNewestFirst(missing)) {
                Long id = pi.getIngredient().getId();
                if (asOf == null || !pi.getPurchase().getPurchaseDate().isAfter(asOf)) {
                    before.putIfAbsent(id, pi);
                } else {
                    after.put(id, pi); // newest first → последняя запись = самая ранняя после asOf
                }
            }
            for (Long id : missing) {
                PurchaseItem pi = before.getOrDefault(id, after.get(id));
                if (pi != null) {
                    cache.put(id, Optional.of(new Price(
                            pi.getTotalPrice().divide(pi.getQuantity(), 2, RoundingMode.HALF_UP),
                            pi.getPurchase().getPurchaseDate(), Source.PURCHASE)));
                }
            }
        }

        private Optional<Price> semiFinishedPrice(Ingredient ingredient) {
            Recipe recipe = semiRecipes.get(key(ingredient.getName()));
            if (recipe == null || recipe.getYieldQuantity() == null || recipe.getYieldQuantity().signum() <= 0
                    || !inProgress.add(ingredient.getId())) {
                return Optional.empty();
            }
            try {
                List<Ingredient> parts = recipe.getItems().stream().map(RecipeItem::getIngredient).toList();
                Map<Long, Price> partPrices = prices(parts);
                BigDecimal total = BigDecimal.ZERO;
                for (RecipeItem ri : recipe.getItems()) {
                    Price p = partPrices.get(ri.getIngredient().getId());
                    if (p != null) total = total.add(p.unitPrice().multiply(ri.getQuantity()));
                }
                if (total.signum() == 0) return Optional.empty();
                return Optional.of(new Price(total.divide(recipe.getYieldQuantity(), 2, RoundingMode.HALF_UP), null, Source.SEMI_FINISHED));
            } finally {
                inProgress.remove(ingredient.getId());
            }
        }
    }
}
