package com.confectionery.crm.service;

import com.confectionery.crm.domain.Ingredient;
import com.confectionery.crm.domain.Product;
import com.confectionery.crm.domain.ProductAlias;
import com.confectionery.crm.domain.Recipe;
import com.confectionery.crm.domain.RecipeItem;
import com.confectionery.crm.repository.ClientPriceRepository;
import com.confectionery.crm.repository.IngredientRepository;
import com.confectionery.crm.repository.OrderRepository;
import com.confectionery.crm.repository.ProductAliasRepository;
import com.confectionery.crm.repository.ProductRepository;
import com.confectionery.crm.repository.ProductionRequestRepository;
import com.confectionery.crm.repository.RecipeRepository;
import com.confectionery.crm.repository.StockMovementRepository;
import com.confectionery.crm.repository.WhatsAppInboundMessageRepository;
import com.confectionery.crm.web.dto.RecipeForm;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final RecipeRepository recipeRepository;
    private final IngredientRepository ingredientRepository;
    private final ProductAliasRepository productAliasRepository;
    private final ClientPriceRepository clientPriceRepository;
    private final OrderRepository orderRepository;
    private final StockMovementRepository stockMovementRepository;
    private final ProductionRequestRepository productionRequestRepository;
    private final WhatsAppInboundMessageRepository whatsAppInboundMessageRepository;

    public List<Product> findAll() {
        return productRepository.findAll();
    }

    /**
     * Без архивных и без полуфабрикатов — каталог для новых заказов (веб-форма, витрина /shop,
     * мобильное API, разбор WhatsApp). Полуфабрикаты (см. Product.semiFinished) физически не
     * продаются клиентам — это заготовка для рецепта другого товара (см. ProductController).
     * Для управления товарами/рецептами (где полуфабрикат как раз нужно видеть) — см.
     * {@link #findAllActiveForManagement()}.
     */
    public List<Product> findAllActive() {
        return findAllActiveForManagement().stream().filter(p -> !p.isSemiFinished()).toList();
    }

    /**
     * Без архивных, но С полуфабрикатами — для экранов управления товарами/рецептами в
     * мобильном приложении (там один общий эндпоинт GET /api/products и на список товаров, и
     * на витрину заказа, см. ProductApiController — includeSemiFinished различает эти случаи).
     */
    public List<Product> findAllActiveForManagement() {
        return productRepository.findByActiveTrue();
    }

    public Product findById(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Товар не найден: " + id));
    }

    public Product save(Product product) {
        return productRepository.save(product);
    }

    public enum DeleteOutcome { DELETED, ARCHIVED }

    /**
     * Товар, который уже где-то "засветился" (в заказах — включая оплаченные/отгруженные, в
     * складских движениях, в заявках на производство или в разобранных WhatsApp-сообщениях),
     * удалить целиком нельзя — это стёрло бы позиции из ЧУЖИХ исторических заказов и задним
     * числом изменило их суммы. Вместо этого товар архивируется (active=false): пропадает из
     * каталога для новых заказов, но старые заказы остаются нетронутыми. Полностью удаляются
     * только "свои" настройки товара — синонимы для WhatsApp и индивидуальные цены клиентов.
     * Полное удаление из БД доступно только для товара без единого следа использования.
     */
    @Transactional
    public DeleteOutcome delete(Long id) {
        Product product = findById(id);
        boolean hasHistory = orderRepository.existsByItems_Product_Id(id)
                || stockMovementRepository.existsByProductId(id)
                || productionRequestRepository.existsByLinesProductId(id)
                || whatsAppInboundMessageRepository.existsByItems_Product_Id(id);
        if (hasHistory) {
            product.setActive(false);
            productRepository.save(product);
            return DeleteOutcome.ARCHIVED;
        }
        clientPriceRepository.deleteAll(clientPriceRepository.findByProductId(id));
        productAliasRepository.deleteAll(productAliasRepository.findByProductId(id));
        productRepository.delete(product); // рецепт (Recipe) каскадится автоматически — см. Product.recipe
        return DeleteOutcome.DELETED;
    }

    public void restore(Long id) {
        Product product = findById(id);
        product.setActive(true);
        productRepository.save(product);
    }

    private static final String NO_CATEGORY = "Без категории";

    /**
     * Товары по категориям для веб-каталога (/products, /warehouse/stock) — категории по
     * алфавиту, "Без категории" всегда последней. Product.category — обычная свободная
     * строка (см. Product.java), без отдельной сущности/enum, поэтому группируем прямо тут,
     * а не в БД.
     */
    public Map<String, List<Product>> groupByCategory(List<Product> products) {
        Map<String, List<Product>> byCategory = products.stream()
                .collect(Collectors.groupingBy(
                        p -> p.getCategory() == null || p.getCategory().isBlank() ? NO_CATEGORY : p.getCategory(),
                        TreeMap::new, Collectors.toList()));
        List<Product> noCategory = byCategory.remove(NO_CATEGORY);
        Map<String, List<Product>> result = new LinkedHashMap<>(byCategory);
        if (noCategory != null) {
            result.put(NO_CATEGORY, noCategory);
        }
        return result;
    }

    private static final String NO_GROUP = "Без группы";

    /**
     * Товары по группам для страницы "Рецепты" (/products/recipes) — та же логика, что и
     * groupByCategory выше, но по Product.group (используется отдельно для разбиения заявок
     * на сырьё, см. ProductionRequestService.createGrouped).
     */
    public Map<String, List<Product>> groupByGroup(List<Product> products) {
        Map<String, List<Product>> byGroup = products.stream()
                .collect(Collectors.groupingBy(
                        p -> p.getGroup() == null || p.getGroup().isBlank() ? NO_GROUP : p.getGroup(),
                        TreeMap::new, Collectors.toList()));
        List<Product> noGroup = byGroup.remove(NO_GROUP);
        Map<String, List<Product>> result = new LinkedHashMap<>(byGroup);
        if (noGroup != null) {
            result.put(NO_GROUP, noGroup);
        }
        return result;
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

    /** Товары (кроме excludeId), у которых уже есть рецептура — для копирования на странице рецепта. */
    public List<Product> findProductsWithRecipe(Long excludeId) {
        return recipeRepository.findAll().stream()
                .map(Recipe::getProduct)
                .filter(p -> !p.getId().equals(excludeId))
                .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                .toList();
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
