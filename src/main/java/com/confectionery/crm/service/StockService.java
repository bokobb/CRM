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
import java.time.LocalDateTime;
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
            throw new IllegalArgumentException("На складе «" + ingredient.getName() + "» меньше ("
                    + ingredient.getQuantityInStock() + " " + ingredient.getUnit() + "), чем указано для списания ("
                    + quantity + " " + ingredient.getUnit() + ")");
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
            String unit = product.getUnit() == null || product.getUnit().isBlank() ? "шт" : product.getUnit();
            throw new IllegalArgumentException("На складе «" + product.getName() + "» меньше ("
                    + product.getQuantityInStock() + " " + unit + "), чем указано для списания ("
                    + quantity + " " + unit + ")");
        }
        product.setQuantityInStock(product.getQuantityInStock().subtract(quantity));
        productRepository.save(product);
        return logMovement(null, product, StockMovementType.WRITE_OFF, quantity, reason, by);
    }

    /**
     * Списание сырья при сборке заявки на производство (ProductionRequestService.collect) —
     * логика та же, что у {@link #writeOff}, но движение помечается неручным (см.
     * StockMovement.manual) и не засоряет страницы "Поступления"/"Списание": сам список сырья
     * заявки уже виден в её карточке.
     */
    @Transactional
    public StockMovement writeOffForProduction(Ingredient ingredient, BigDecimal quantity, String reason, String by) {
        requirePositive(quantity, "списания");
        if (ingredient.getQuantityInStock().compareTo(quantity) < 0) {
            throw new IllegalArgumentException("На складе «" + ingredient.getName() + "» меньше ("
                    + ingredient.getQuantityInStock() + " " + ingredient.getUnit() + "), чем указано для списания ("
                    + quantity + " " + ingredient.getUnit() + ")");
        }
        ingredient.setQuantityInStock(ingredient.getQuantityInStock().subtract(quantity));
        ingredientRepository.save(ingredient);
        return logMovement(ingredient, null, StockMovementType.WRITE_OFF, quantity, reason, by, false);
    }

    /**
     * Обратное {@link #writeOffForProduction} — возврат сырья на склад при пересмотре состава
     * уже собранной заявки (ProductionRequestService.substituteItem: уменьшили количество или
     * заменили сырьё после того, как прежнее уже было списано в {@link #writeOffForProduction}).
     */
    @Transactional
    public StockMovement receiveForProduction(Ingredient ingredient, BigDecimal quantity, String reason, String by) {
        requirePositive(quantity, "поступления");
        ingredient.setQuantityInStock(ingredient.getQuantityInStock().add(quantity));
        ingredientRepository.save(ingredient);
        return logMovement(ingredient, null, StockMovementType.RECEIPT, quantity, reason, by, false);
    }

    /**
     * Оприходование строки заявки на производство (ProductionRequestService.receiveLine) — см.
     * {@link #writeOffForProduction}. Для полуфабриката (Product.semiFinished, например
     * "Пряник" — испечён без упаковки, сам идёт сырьём в рецепт другого товара) остаток ЗЕРКАЛИТСЯ
     * в одноимённую позицию справочника "Сырьё" (сопоставление по точному названию — тот же
     * приём, что и везде в интеграции с 1С) — именно она указывается в рецепте товара,
     * упаковывающего этот полуфабрикат. Если такого сырья ещё нет в справочнике — не создаём
     * его молча, а требуем завести вручную на странице "Сырьё" (то же название), иначе рецепт
     * дня 2 будет не на что ссылаться.
     */
    @Transactional
    public StockMovement receiveProductForProduction(Product product, BigDecimal quantity, String reason, String by) {
        requirePositive(quantity, "поступления");
        product.setQuantityInStock(product.getQuantityInStock().add(quantity));
        productRepository.save(product);
        if (product.isSemiFinished()) {
            Ingredient mirror = ingredientRepository.findByNameIgnoreCase(product.getName())
                    .orElseThrow(() -> new IllegalStateException("Полуфабрикат «" + product.getName()
                            + "» отмечен как сырьё, но в справочнике «Сырьё» нет позиции с таким же названием — заведите её"));
            mirror.setQuantityInStock(mirror.getQuantityInStock().add(quantity));
            ingredientRepository.save(mirror);
            logMovement(mirror, null, StockMovementType.RECEIPT, quantity, reason, by, false);
        }
        return logMovement(null, product, StockMovementType.RECEIPT, quantity, reason, by, false);
    }

    private void requirePositive(BigDecimal quantity, String action) {
        if (quantity == null || quantity.signum() <= 0) {
            throw new IllegalArgumentException("Количество " + action + " должно быть больше нуля");
        }
    }

    private StockMovement logMovement(Ingredient ingredient, Product product, StockMovementType type,
                                       BigDecimal quantity, String reason, String by) {
        return logMovement(ingredient, product, type, quantity, reason, by, true);
    }

    private StockMovement logMovement(Ingredient ingredient, Product product, StockMovementType type,
                                       BigDecimal quantity, String reason, String by, boolean manual) {
        StockMovement movement = new StockMovement();
        movement.setIngredient(ingredient);
        movement.setProduct(product);
        movement.setType(type);
        movement.setQuantity(quantity);
        movement.setReason(reason);
        movement.setCreatedBy(by);
        movement.setManual(manual);
        return stockMovementRepository.save(movement);
    }

    /**
     * Удаляет ошибочно созданное РУЧНОЕ движение и откатывает его влияние на остаток (приход —
     * вычитаем обратно, списание — возвращаем обратно). Автоматические движения (заявки на
     * производство, см. StockMovement.manual) удалить так нельзя — их отменяют через саму
     * заявку. Уже выгруженные в 1С (см. StockMovement.oneCSyncedAt/OneCReceiptSyncService)
     * тоже нельзя — иначе CRM молча разойдётся с документом, который уже есть в 1С; такое
     * движение сначала нужно удалить/исправить в самой 1С.
     */
    @Transactional
    public void deleteMovement(Long movementId) {
        StockMovement movement = stockMovementRepository.findById(movementId)
                .orElseThrow(() -> new IllegalArgumentException("Движение не найдено: " + movementId));
        if (!movement.isManual()) {
            throw new IllegalStateException("Нельзя удалить движение, созданное автоматически заявкой на производство");
        }
        if (movement.getOneCSyncedAt() != null) {
            throw new IllegalStateException("Уже выгружено в 1С документом №" + movement.getOneCDocumentNumber()
                    + " — сначала удалите его там");
        }
        BigDecimal delta = movement.getType() == StockMovementType.RECEIPT
                ? movement.getQuantity().negate()
                : movement.getQuantity();
        if (movement.getProduct() != null) {
            Product product = movement.getProduct();
            product.setQuantityInStock(product.getQuantityInStock().add(delta));
            productRepository.save(product);
        } else {
            Ingredient ingredient = movement.getIngredient();
            ingredient.setQuantityInStock(ingredient.getQuantityInStock().add(delta));
            ingredientRepository.save(ingredient);
        }
        stockMovementRepository.delete(movement);
    }

    /**
     * Только ручные движения (см. StockMovement.manual). from/to/nameQuery необязательны:
     * from/to сужают по дате (from включительно, to — начало следующего дня, т.е. по дату to
     * включительно), nameQuery ищет подстроку без учёта регистра в названии сырья/товара —
     * это уже не в SQL, а в Java, т.к. имя лежит в связанной сущности, а не в самой строке.
     */
    public List<StockMovement> findMovements(StockMovementType type, LocalDateTime from, LocalDateTime to, String nameQuery) {
        List<StockMovement> movements = stockMovementRepository.findManual(type, from, to);
        if (nameQuery == null || nameQuery.isBlank()) {
            return movements;
        }
        String q = nameQuery.trim().toLowerCase();
        return movements.stream()
                .filter(m -> itemName(m).toLowerCase().contains(q))
                .toList();
    }

    private String itemName(StockMovement m) {
        return m.getProduct() != null ? m.getProduct().getName() : m.getIngredient().getName();
    }
}
