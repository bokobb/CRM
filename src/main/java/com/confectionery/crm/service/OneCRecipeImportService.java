package com.confectionery.crm.service;

import com.confectionery.crm.domain.Ingredient;
import com.confectionery.crm.domain.Product;
import com.confectionery.crm.repository.IngredientRepository;
import com.confectionery.crm.repository.ProductRepository;
import com.confectionery.crm.web.dto.RecipeForm;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Разовый (по кнопке, не по расписанию — рецепты меняются редко, действие затрагивает уже
 * введённые вручную рецепты) импорт рецептур из 1С — регистр "Спецификации номенклатуры".
 * У некоторых товаров в 1С спецификация разбита на 2 части (например, "для сырья"/тесто и
 * "упаковка") — вторая ссылается на первую как на один из своих компонентов (сам на себя,
 * через Owner_Key). Здесь это разворачивается в один плоский список сырья (см. {@link #resolve}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OneCRecipeImportService {

    private final OneCClient oneCClient;
    private final ProductRepository productRepository;
    private final IngredientRepository ingredientRepository;
    private final ProductService productService;

    public ImportResult importAll() {
        if (!oneCClient.isConfigured()) {
            throw new IllegalStateException("Подключение к 1С не настроено (crm.onec.base-url/username/password)");
        }

        Map<String, String> names = oneCClient.fetchNomenclatureNames();
        Map<String, List<OneCClient.Specification>> byOwner = oneCClient.fetchSpecifications().stream()
                .collect(Collectors.groupingBy(OneCClient.Specification::ownerKey));

        Map<String, String> keyByExactName = new HashMap<>();
        for (Map.Entry<String, String> e : names.entrySet()) {
            keyByExactName.putIfAbsent(e.getValue().trim().toLowerCase(), e.getKey());
        }

        Map<String, Ingredient> ingredientByName = new HashMap<>();
        for (Ingredient ingredient : ingredientRepository.findAll()) {
            ingredientByName.putIfAbsent(ingredient.getName().trim().toLowerCase(), ingredient);
        }

        int imported = 0;
        int skippedNoOneCMatch = 0;
        int skippedNoSpec = 0;
        List<String> warnings = new ArrayList<>();

        for (Product product : productRepository.findAll()) {
            String key = keyByExactName.get(product.getName().trim().toLowerCase());
            if (key == null) {
                skippedNoOneCMatch++;
                continue;
            }
            if (!byOwner.containsKey(key)) {
                skippedNoSpec++;
                continue;
            }

            Resolved resolved = resolve(key, byOwner, 0);
            if (resolved == null || resolved.raw().isEmpty()) {
                skippedNoSpec++;
                continue;
            }

            BigDecimal factor = precisionFactor(resolved.raw().values());

            RecipeForm form = new RecipeForm();
            form.setYieldQuantity(resolved.yield().multiply(factor).setScale(3, RoundingMode.HALF_UP));
            for (Map.Entry<String, BigDecimal> e : resolved.raw().entrySet()) {
                String ingName = names.getOrDefault(e.getKey(), "").trim().toLowerCase();
                Ingredient ingredient = ingredientByName.get(ingName);
                if (ingredient == null) {
                    warnings.add(product.getName() + ": не найдено сырьё \"" + names.get(e.getKey()) + "\"");
                    continue;
                }
                RecipeForm.RecipeItemForm itemForm = new RecipeForm.RecipeItemForm();
                itemForm.setIngredientId(ingredient.getId());
                itemForm.setQuantity(e.getValue().multiply(factor).setScale(3, RoundingMode.HALF_UP));
                form.getItems().add(itemForm);
            }
            if (form.getItems().isEmpty()) {
                skippedNoSpec++;
                continue;
            }
            productService.saveRecipe(product.getId(), form);
            imported++;
        }

        return new ImportResult(imported, skippedNoOneCMatch, skippedNoSpec, warnings);
    }

    /**
     * Множитель для количества и партии рецепта — БД хранит с точностью до 3 знаков после
     * запятой, а у некоторых товаров (после разворачивания вложенной спецификации) самая
     * мелкая позиция (например, лимонная кислота) может выражаться тысячными долями штуки.
     * Домножаем партию и все количества на 10/100/1000, чтобы не потерять такие ингредиенты
     * при округлении — на итоговое соотношение сырьё/продукция (и на списание) это не влияет.
     */
    private BigDecimal precisionFactor(java.util.Collection<BigDecimal> quantities) {
        BigDecimal minQty = quantities.stream()
                .filter(q -> q.signum() != 0)
                .min(Comparator.naturalOrder())
                .orElse(BigDecimal.ONE);
        BigDecimal factor = BigDecimal.ONE;
        BigDecimal threshold = new BigDecimal("0.01");
        BigDecimal cap = new BigDecimal("1000");
        while (minQty.multiply(factor).compareTo(threshold) < 0 && factor.compareTo(cap) < 0) {
            factor = factor.multiply(BigDecimal.TEN);
        }
        return factor;
    }

    private OneCClient.Specification pickTopSpec(String key, Map<String, List<OneCClient.Specification>> byOwner) {
        List<OneCClient.Specification> list = byOwner.get(key);
        if (list.size() == 1) return list.get(0);
        return list.stream()
                .filter(s -> s.description().toLowerCase().contains("упаков"))
                .findFirst()
                .orElseGet(() -> list.stream().max(Comparator.comparingInt(s -> s.lines().size())).orElseThrow());
    }

    private OneCClient.Specification pickSubSpec(String key, String excludeRef, Map<String, List<OneCClient.Specification>> byOwner) {
        return byOwner.get(key).stream().filter(s -> !s.ref().equals(excludeRef)).findFirst().orElse(null);
    }

    /**
     * Разворачивает спецификацию товара key в плоский список сырья. Если строка спецификации
     * ссылается на сам key (типичная схема "упаковка использует 1 шт. себя же, произведённую
     * по другой, "сырьевой" спецификации того же товара) — подставляется та, другая
     * спецификация. Если строка ссылается на ДРУГОЙ товар, у которого тоже есть своя
     * спецификация (отдельный полуфабрикат, напр. "Рогалики для сырья") — разворачивается
     * рекурсивно. depth ограничивает глубину на случай зацикленных данных в самой 1С.
     */
    private Resolved resolve(String key, Map<String, List<OneCClient.Specification>> byOwner, int depth) {
        List<OneCClient.Specification> list = byOwner.get(key);
        if (list == null) return null;
        OneCClient.Specification top = pickTopSpec(key, byOwner);
        Map<String, BigDecimal> raw = new LinkedHashMap<>();

        for (OneCClient.SpecLine line : top.lines()) {
            String ingKey = line.ingredientKey();
            BigDecimal qty = line.quantity();
            if (ingKey.equals(key)) {
                OneCClient.Specification sub = pickSubSpec(key, top.ref(), byOwner);
                if (sub == null) {
                    add(raw, ingKey, qty);
                    continue;
                }
                BigDecimal subYield = sub.yieldQuantity().signum() != 0 ? sub.yieldQuantity() : BigDecimal.ONE;
                BigDecimal scale = qty.divide(subYield, MathContext.DECIMAL64);
                for (OneCClient.SpecLine subLine : sub.lines()) {
                    mergeExpanded(raw, subLine.ingredientKey(), subLine.quantity().multiply(scale), key, byOwner, depth);
                }
            } else {
                mergeExpanded(raw, ingKey, qty, key, byOwner, depth);
            }
        }
        return new Resolved(top.yieldQuantity(), raw);
    }

    private void mergeExpanded(Map<String, BigDecimal> raw, String ingKey, BigDecimal qty, String parentKey,
                                Map<String, List<OneCClient.Specification>> byOwner, int depth) {
        if (byOwner.containsKey(ingKey) && !ingKey.equals(parentKey) && depth < 5) {
            Resolved nested = resolve(ingKey, byOwner, depth + 1);
            if (nested != null) {
                BigDecimal nestedYield = nested.yield().signum() != 0 ? nested.yield() : BigDecimal.ONE;
                BigDecimal nestedScale = qty.divide(nestedYield, MathContext.DECIMAL64);
                for (Map.Entry<String, BigDecimal> e : nested.raw().entrySet()) {
                    add(raw, e.getKey(), e.getValue().multiply(nestedScale));
                }
                return;
            }
        }
        add(raw, ingKey, qty);
    }

    private void add(Map<String, BigDecimal> map, String key, BigDecimal value) {
        map.merge(key, value, BigDecimal::add);
    }

    private record Resolved(BigDecimal yield, Map<String, BigDecimal> raw) {
    }

    public record ImportResult(int imported, int skippedNoOneCMatch, int skippedNoSpec, List<String> warnings) {
    }
}
