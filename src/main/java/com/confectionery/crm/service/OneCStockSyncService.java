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
import java.time.Duration;
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
 *
 * <p>Пропускается, если давно не было реальных пользователей (см. {@link UserActivityTracker},
 * {@code crm.onec.idle-skip-minutes}) — запрос к 1С медленный (полный перебор справочника
 * номенклатуры + регистра остатков), гонять его каждые 5 минут круглые сутки ради данных,
 * которые в этот момент никто не смотрит, не нужно (2026-09-22, жалоба на общее замедление).
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
    private final UserActivityTracker userActivityTracker;

    @Value("${crm.onec.finished-goods-account-code:1320}")
    private String finishedGoodsAccountCode;
    @Value("${crm.onec.materials-account-code:1310}")
    private String materialsAccountCode;

    /**
     * Ещё счета, остатки которых на складе сырья — тоже сырьё CRM. В 1С часть сырья оприходована
     * на 1330 «Товары» (например, «Кукуруз декор», 01.10.2026) — без этого CRM его не видела.
     */
    @Value("${crm.onec.materials-extra-account-codes:1330}")
    private List<String> materialsExtraAccountCodes;
    @Value("${crm.onec.finished-goods-warehouse-key:d2791a64-4f43-11f1-9670-5254007c5efd}")
    private String finishedGoodsWarehouseKey;
    @Value("${crm.onec.materials-warehouse-key:ca2e388a-4f43-11f1-9670-5254007c5efd}")
    private String materialsWarehouseKey;
    @Value("${crm.onec.idle-skip-minutes:10}")
    private int idleSkipMinutes;

    @Scheduled(cron = "${crm.onec.sync-cron:0 0 * * * *}", zone = "${crm.schedule-zone:GMT+05:00}")
    public void scheduledSync() {
        if (!oneCClient.isConfigured()) {
            log.debug("Синхронизация с 1С не настроена (crm.onec.base-url/username/password пусты) — пропускаю");
            return;
        }
        if (!userActivityTracker.recentlyActive(Duration.ofMinutes(idleSkipMinutes))) {
            log.debug("Пользователи неактивны более {} мин — пропускаю синхронизацию с 1С", idleSkipMinutes);
            return;
        }
        try {
            SyncResult result = sync();
            log.info("Синхронизация с 1С: товаров обновлено {}, сырья обновлено {}, снято с продажи {}, сырья снято {}, не сопоставлено {}",
                    result.productsUpdated(), result.ingredientsUpdated(), result.productsDeactivated(),
                    result.ingredientsDeactivated(), result.unmatched().size());
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
        Set<String> materialsAccountKeys = new HashSet<>();
        materialsAccountKeys.add(materialsAccountKey);
        for (String code : materialsExtraAccountCodes) {
            String key = oneCClient.findAccountKey(code.trim());
            if (key != null) materialsAccountKeys.add(key);
        }
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
        Map<Ingredient, BigDecimal> ingredientTotals = new java.util.LinkedHashMap<>();

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
            } else if (materialsAccountKeys.contains(row.accountKey()) && row.warehouseKey().equals(materialsWarehouseKey)) {
                Ingredient ingredient = matchIngredient(name, ingredients);
                if (ingredient == null) {
                    unmatched.add(NOMENCLATURE_KIND_INGREDIENT + ": " + name);
                    continue;
                }
                seenIngredientIds.add(ingredient.getId());
                // Одно сырьё может лежать на нескольких счетах (1310 и 1330) — складываем.
                ingredientTotals.merge(ingredient, row.quantity(), BigDecimal::add);
            }
        }
        for (Map.Entry<Ingredient, BigDecimal> e : ingredientTotals.entrySet()) {
            if (applyIngredientQuantity(e.getKey(), e.getValue())) ingredientsUpdated++;
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

        Set<String> deletedKeys = oneCClient.fetchDeletedNomenclatureKeys();
        // Позиции CRM без GUID номенклатуры сопоставляем по точному названию — но только если
        // одноимённой НЕпомеченной номенклатуры в 1С нет (иначе это просто тёзка удалённой).
        Set<String> deletedNames = new HashSet<>();
        Set<String> liveNames = new HashSet<>();
        nomenclatureNames.forEach((key, name) -> {
            if (name == null || name.isBlank()) return;
            (deletedKeys.contains(key) ? deletedNames : liveNames).add(normalizeName(name));
        });
        deletedNames.removeAll(liveNames);

        int productsDeactivated = deactivateDeletedInOneC(products, deletedKeys, deletedNames);
        int ingredientsDeactivated = deactivateDeletedIngredientsInOneC(ingredients, deletedKeys, deletedNames);

        return new SyncResult(productsUpdated, ingredientsUpdated, productsDeactivated, ingredientsDeactivated, unmatched);
    }

    /**
     * Товар, чья номенклатура в 1С помечена на удаление, продаваться не должен — снимаем его с
     * продажи (active=false: пропадает из витрины, формы заказа, разбора WhatsApp; новые позиции
     * с ним OrderService не примет). Сопоставление по GUID номенклатуры. Обратно НЕ включаем,
     * если пометку в 1С сняли: active=false ставит и ручная архивация товара с историей, и
     * отличить одно от другого нельзя — вернуть товар в продажу можно вручную в карточке.
     */
    private int deactivateDeletedInOneC(List<Product> products, Set<String> deletedKeys, Set<String> deletedNames) {
        int deactivated = 0;
        for (Product product : products) {
            if (!product.isActive()) continue;
            if (!isDeletedInOneC(product.getOneCNomenclatureKey(), product.getName(), deletedKeys, deletedNames)) continue;
            product.setActive(false);
            productRepository.save(product);
            log.info("Товар №{} «{}» снят с продажи — в 1С номенклатура помечена на удаление",
                    product.getId(), product.getName());
            deactivated++;
        }
        return deactivated;
    }

    /**
     * Прямое обновление остатка, БЕЗ записи в StockMovement — синхронизация идёт каждые 5 минут
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

    /** То же, что {@link #deactivateDeletedInOneC}, для сырья (см. Ingredient#active). */
    private int deactivateDeletedIngredientsInOneC(List<Ingredient> ingredients, Set<String> deletedKeys, Set<String> deletedNames) {
        int deactivated = 0;
        for (Ingredient ingredient : ingredients) {
            if (!ingredient.isActive()) continue;
            if (!isDeletedInOneC(ingredient.getOneCNomenclatureKey(), ingredient.getName(), deletedKeys, deletedNames)) continue;
            ingredient.setActive(false);
            ingredientRepository.save(ingredient);
            log.info("Сырьё №{} «{}» снято с использования — в 1С номенклатура помечена на удаление",
                    ingredient.getId(), ingredient.getName());
            deactivated++;
        }
        return deactivated;
    }

    /** По GUID, если он есть; без GUID — по точному названию (см. deletedNames в {@link #sync()}). */
    private static boolean isDeletedInOneC(String key, String name, Set<String> deletedKeys, Set<String> deletedNames) {
        if (key != null) return deletedKeys.contains(key);
        return name != null && deletedNames.contains(normalizeName(name));
    }

    private static String normalizeName(String name) {
        return name.trim().replaceAll("\\s+", " ").toLowerCase();
    }

    public record SyncResult(int productsUpdated, int ingredientsUpdated, int productsDeactivated,
                             int ingredientsDeactivated, List<String> unmatched) {
    }
}
