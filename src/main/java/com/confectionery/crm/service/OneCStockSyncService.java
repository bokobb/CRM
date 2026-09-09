package com.confectionery.crm.service;

import com.confectionery.crm.domain.Ingredient;
import com.confectionery.crm.domain.Product;
import com.confectionery.crm.repository.IngredientRepository;
import com.confectionery.crm.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Синхронизация остатков из 1С:Fresh — только чтение, обратно в 1С ничего не пишем.
 * Источник — регистр бухгалтерии по счетам плана счетов (см. OneCClient): счёт "Готовая
 * продукция" на складе готовой продукции обновляет {@link Product#getQuantityInStock()},
 * счёт "Сырье и материалы" на складе сырья обновляет {@link Ingredient#getQuantityInStock()}.
 * Сопоставление — по точному названию номенклатуры (см. ProductMatcher для товаров).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OneCStockSyncService {

    private static final String NOMENCLATURE_KIND_PRODUCT = "Товар";
    private static final String NOMENCLATURE_KIND_INGREDIENT = "Сырьё";

    private final OneCClient oneCClient;
    private final ProductRepository productRepository;
    private final IngredientRepository ingredientRepository;

    @Value("${crm.onec.finished-goods-account-code:1320}")
    private String finishedGoodsAccountCode;
    @Value("${crm.onec.materials-account-code:1310}")
    private String materialsAccountCode;
    @Value("${crm.onec.finished-goods-warehouse-key:d2791a64-4f43-11f1-9670-5254007c5efd}")
    private String finishedGoodsWarehouseKey;
    @Value("${crm.onec.materials-warehouse-key:ca2e388a-4f43-11f1-9670-5254007c5efd}")
    private String materialsWarehouseKey;

    @Scheduled(cron = "${crm.onec.sync-cron:0 0 * * * *}")
    public void scheduledSync() {
        if (!oneCClient.isConfigured()) {
            log.debug("Синхронизация с 1С не настроена (crm.onec.base-url/username/password пусты) — пропускаю");
            return;
        }
        try {
            SyncResult result = sync();
            log.info("Синхронизация с 1С: товаров обновлено {}, сырья обновлено {}, не сопоставлено {}",
                    result.productsUpdated(), result.ingredientsUpdated(), result.unmatched().size());
            if (!result.unmatched().isEmpty()) {
                log.warn("Не сопоставлено с каталогом CRM: {}", result.unmatched());
            }
        } catch (Exception e) {
            log.warn("Синхронизация с 1С не удалась: {}", e.getMessage());
        }
    }

    /** Ручной запуск (см. WarehouseStockController) — бросает исключение наружу, чтобы показать причину в UI. */
    public SyncResult sync() {
        if (!oneCClient.isConfigured()) {
            throw new IllegalStateException("Подключение к 1С не настроено (crm.onec.base-url/username/password)");
        }

        String finishedGoodsAccountKey = oneCClient.findAccountKey(finishedGoodsAccountCode);
        String materialsAccountKey = oneCClient.findAccountKey(materialsAccountCode);
        if (finishedGoodsAccountKey == null || materialsAccountKey == null) {
            throw new IllegalStateException("В плане счетов 1С не найдены счета "
                    + finishedGoodsAccountCode + "/" + materialsAccountCode);
        }

        Map<String, String> nomenclatureNames = oneCClient.fetchNomenclatureNames();
        List<OneCClient.AccountBalanceRow> rows = oneCClient.fetchAccountBalances();

        List<Product> products = productRepository.findAll();
        List<Ingredient> ingredients = ingredientRepository.findAll();

        int productsUpdated = 0;
        int ingredientsUpdated = 0;
        List<String> unmatched = new ArrayList<>();
        Set<Long> seenProductIds = new HashSet<>();
        Set<Long> seenIngredientIds = new HashSet<>();

        for (OneCClient.AccountBalanceRow row : rows) {
            String name = nomenclatureNames.get(row.nomenclatureKey());
            if (name == null || name.isBlank()) continue;

            if (row.accountKey().equals(finishedGoodsAccountKey) && row.warehouseKey().equals(finishedGoodsWarehouseKey)) {
                Product product = ProductMatcher.match(name, products);
                if (product == null) {
                    unmatched.add(NOMENCLATURE_KIND_PRODUCT + ": " + name);
                    continue;
                }
                seenProductIds.add(product.getId());
                if (applyProductQuantity(product, row.quantity())) productsUpdated++;
            } else if (row.accountKey().equals(materialsAccountKey) && row.warehouseKey().equals(materialsWarehouseKey)) {
                Ingredient ingredient = matchIngredient(name, ingredients);
                if (ingredient == null) {
                    unmatched.add(NOMENCLATURE_KIND_INGREDIENT + ": " + name);
                    continue;
                }
                seenIngredientIds.add(ingredient.getId());
                if (applyIngredientQuantity(ingredient, row.quantity())) ingredientsUpdated++;
            }
        }

        // Регистр остатков 1С отдаёт строку только для НЕнулевого остатка — если товар/сырьё
        // полностью закончилось в 1С, строки для него в rows вообще не будет (это норма для
        // регистров остатков, не ошибка 1С). Поэтому то, что есть в каталоге 1С (по названию),
        // но не встретилось ни в одной строке остатков, обнуляем явно — иначе остаток в CRM
        // так и останется прежним, "зависшим" на последнем ненулевом значении.
        Set<String> oneCNames = new HashSet<>();
        for (String name : nomenclatureNames.values()) {
            if (name != null && !name.isBlank()) oneCNames.add(name.trim().toLowerCase());
        }
        for (Product product : products) {
            if (seenProductIds.contains(product.getId())) continue;
            if (!oneCNames.contains(product.getName().trim().toLowerCase())) continue;
            if (applyProductQuantity(product, BigDecimal.ZERO)) productsUpdated++;
        }
        for (Ingredient ingredient : ingredients) {
            if (seenIngredientIds.contains(ingredient.getId())) continue;
            if (!oneCNames.contains(ingredient.getName().trim().toLowerCase())) continue;
            if (applyIngredientQuantity(ingredient, BigDecimal.ZERO)) ingredientsUpdated++;
        }

        return new SyncResult(productsUpdated, ingredientsUpdated, unmatched);
    }

    /**
     * Прямое обновление остатка, БЕЗ записи в StockMovement — синхронизация идёт раз в час
     * и трогает десятки позиций разом, отдельная запись "Синхронизация с 1С" на каждую
     * засоряла бы страницы "Поступления"/"Списание" (они для ручных движений склада).
     */
    private boolean applyProductQuantity(Product product, BigDecimal newQuantity) {
        if (newQuantity.compareTo(product.getQuantityInStock()) == 0) return false;
        product.setQuantityInStock(newQuantity);
        productRepository.save(product);
        return true;
    }

    private boolean applyIngredientQuantity(Ingredient ingredient, BigDecimal newQuantity) {
        if (newQuantity.compareTo(ingredient.getQuantityInStock()) == 0) return false;
        ingredient.setQuantityInStock(newQuantity);
        ingredientRepository.save(ingredient);
        return true;
    }

    private Ingredient matchIngredient(String name, List<Ingredient> ingredients) {
        String needle = name.trim().toLowerCase();
        for (Ingredient ingredient : ingredients) {
            if (ingredient.getName().trim().toLowerCase().equals(needle)) return ingredient;
        }
        return null;
    }

    public record SyncResult(int productsUpdated, int ingredientsUpdated, List<String> unmatched) {
    }
}
