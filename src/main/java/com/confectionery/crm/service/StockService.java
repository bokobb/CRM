package com.confectionery.crm.service;

import com.confectionery.crm.domain.Ingredient;
import com.confectionery.crm.domain.Product;
import com.confectionery.crm.domain.StockDocument;
import com.confectionery.crm.domain.StockMovement;
import com.confectionery.crm.domain.StockMovementType;
import com.confectionery.crm.repository.IngredientRepository;
import com.confectionery.crm.repository.ProductRepository;
import com.confectionery.crm.repository.StockDocumentRepository;
import com.confectionery.crm.repository.StockMovementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Ручные поступления/списания с журналом (StockMovement), которые ведёт зав. складом через
 * раздел "Склад" в приложении, плюс приход сырья по закупкам (см. PurchaseService).
 */
@Service
@RequiredArgsConstructor
public class StockService {

    private final IngredientRepository ingredientRepository;
    private final ProductRepository productRepository;
    private final StockMovementRepository stockMovementRepository;
    private final StockDocumentRepository stockDocumentRepository;
    private final OneCQueue oneCQueue;

    @Transactional
    public StockMovement receive(Ingredient ingredient, BigDecimal quantity, String reason, String by) {
        return receive(ingredient, quantity, reason, by, null);
    }

    private StockMovement receive(Ingredient ingredient, BigDecimal quantity, String reason, String by, LocalDateTime at) {
        requirePositive(quantity, "поступления");
        ingredient.setQuantityInStock(ingredient.getQuantityInStock().add(quantity));
        ingredientRepository.save(ingredient);
        return logMovement(ingredient, null, StockMovementType.RECEIPT, quantity, reason, by, true, at);
    }

    /**
     * Откат прихода сырья при правке закупки (PurchaseService.update): остаток уменьшается на
     * приход, движение удаляется. Без проверки «хватает ли на складе» — приход мог уже уйти в
     * производство, остаток всё равно сверяется с 1С (OneCStockSyncService).
     */
    @Transactional
    public void revertReceipt(StockMovement movement) {
        Ingredient ingredient = movement.getIngredient();
        ingredient.setQuantityInStock(ingredient.getQuantityInStock().subtract(movement.getQuantity()));
        ingredientRepository.save(ingredient);
        stockMovementRepository.delete(movement);
    }

    private StockMovement writeOff(Ingredient ingredient, BigDecimal quantity, String reason, String by, LocalDateTime at) {
        requirePositive(quantity, "списания");
        if (ingredient.getQuantityInStock().compareTo(quantity) < 0) {
            throw new IllegalArgumentException("На складе «" + ingredient.getName() + "» меньше ("
                    + ingredient.getQuantityInStock() + " " + ingredient.getUnit() + "), чем указано для списания ("
                    + quantity + " " + ingredient.getUnit() + ")");
        }
        ingredient.setQuantityInStock(ingredient.getQuantityInStock().subtract(quantity));
        ingredientRepository.save(ingredient);
        return logMovement(ingredient, null, StockMovementType.WRITE_OFF, quantity, reason, by, true, at);
    }

    /** То же самое, но для готовой продукции (см. также автосписание при отгрузке — OrderService.ship). */
    private StockMovement receiveProduct(Product product, BigDecimal quantity, String reason, String by, LocalDateTime at) {
        requirePositive(quantity, "поступления");
        product.setQuantityInStock(product.getQuantityInStock().add(quantity));
        productRepository.save(product);
        return logMovement(null, product, StockMovementType.RECEIPT, quantity, reason, by, true, at);
    }

    private StockMovement writeOffProduct(Product product, BigDecimal quantity, String reason, String by, LocalDateTime at) {
        requirePositive(quantity, "списания");
        if (product.getQuantityInStock().compareTo(quantity) < 0) {
            String unit = product.getUnit() == null || product.getUnit().isBlank() ? "шт" : product.getUnit();
            throw new IllegalArgumentException("На складе «" + product.getName() + "» меньше ("
                    + product.getQuantityInStock() + " " + unit + "), чем указано для списания ("
                    + quantity + " " + unit + ")");
        }
        product.setQuantityInStock(product.getQuantityInStock().subtract(quantity));
        productRepository.save(product);
        return logMovement(null, product, StockMovementType.WRITE_OFF, quantity, reason, by, true, at);
    }

    /** Строка {@link #createDocument}: ровно одно из ingredient/product. */
    public record DocumentLine(Ingredient ingredient, Product product, BigDecimal quantity) {
    }

    /**
     * Поступление или списание нескольких позиций одной формой (см. StockDocument): всё или
     * ничего — если при списании хоть одной позиции не хватает, не проводится ни одна. У
     * списания причина обязательна. {@code date} — дата документа (не в будущем); время
     * движений — текущее, чтобы порядок в журнале за день сохранялся. После коммита документ
     * уходит в 1С и проводится (фоновая очередь, см. OneCStockDocumentSyncService).
     */
    @Transactional
    public StockDocument createDocument(StockMovementType type, boolean isProduct, LocalDate date, String reason,
                                        List<DocumentLine> lines, String by) {
        boolean writeOff = type == StockMovementType.WRITE_OFF;
        if (writeOff && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException("Укажите причину списания");
        }
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("Добавьте хотя бы одну позицию");
        }
        for (DocumentLine line : lines) {
            if ((line.product() != null) != isProduct || (line.product() == null && line.ingredient() == null)) {
                throw new IllegalArgumentException(isProduct ? "В документе могут быть только товары" : "В документе может быть только сырьё");
            }
        }
        LocalDate day = date != null ? date : LocalDate.now();
        if (day.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("Дата документа не может быть в будущем");
        }
        String text = reason == null || reason.isBlank() ? null : reason.trim();
        LocalDateTime at = LocalDateTime.of(day, LocalTime.now());

        StockDocument doc = new StockDocument();
        doc.setType(type);
        doc.setProduct(isProduct);
        doc.setDocumentDate(day);
        doc.setReason(text);
        doc.setCreatedBy(by);
        doc = stockDocumentRepository.save(doc);

        for (DocumentLine line : lines) {
            StockMovement m;
            if (writeOff) {
                m = isProduct ? writeOffProduct(line.product(), line.quantity(), text, by, at)
                        : writeOff(line.ingredient(), line.quantity(), text, by, at);
            } else {
                m = isProduct ? receiveProduct(line.product(), line.quantity(), text, by, at)
                        : receive(line.ingredient(), line.quantity(), text, by, at);
            }
            m.setDocument(doc);
            doc.getMovements().add(stockMovementRepository.save(m));
        }
        oneCQueue.stockDocument(doc);
        return stockDocumentRepository.save(doc);
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
        // Без проверки остатка: сырьё физически выдано, даже если в CRM его числится меньше
        // (не провели закупку и т.п.) — «Собрано» не блокируется, остаток уходит в минус до сверки.
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

    /**
     * Обратное {@link #receiveProductForProduction} — админ уменьшил оприходованное количество
     * или убрал оприходованную строку из заявки. Без проверки остатка: продукция могла уже уйти
     * в отгрузку, остаток в CRM тогда честно уходит в минус до сверки.
     */
    @Transactional
    public StockMovement revertProductForProduction(Product product, BigDecimal quantity, String reason, String by) {
        requirePositive(quantity, "списания");
        product.setQuantityInStock(product.getQuantityInStock().subtract(quantity));
        productRepository.save(product);
        if (product.isSemiFinished()) {
            ingredientRepository.findByNameIgnoreCase(product.getName()).ifPresent(mirror -> {
                mirror.setQuantityInStock(mirror.getQuantityInStock().subtract(quantity));
                ingredientRepository.save(mirror);
                logMovement(mirror, null, StockMovementType.WRITE_OFF, quantity, reason, by, false);
            });
        }
        return logMovement(null, product, StockMovementType.WRITE_OFF, quantity, reason, by, false);
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
        return logMovement(ingredient, product, type, quantity, reason, by, manual, null);
    }

    /** {@code at} — время движения, если не «сейчас» (createdAt не обновляется после вставки). */
    private StockMovement logMovement(Ingredient ingredient, Product product, StockMovementType type,
                                       BigDecimal quantity, String reason, String by, boolean manual, LocalDateTime at) {
        StockMovement movement = new StockMovement();
        if (at != null) {
            movement.setCreatedAt(at);
        }
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
     * заявку. Выгруженные в 1С без документа формы (см. StockMovement.oneCSyncedAt) тоже
     * нельзя — иначе CRM молча разойдётся с документом, который уже есть в 1С; такое
     * движение сначала нужно удалить/исправить в самой 1С.
     */
    @Transactional
    public void deleteMovement(Long movementId) {
        StockMovement movement = stockMovementRepository.findById(movementId)
                .orElseThrow(() -> new IllegalArgumentException("Движение не найдено: " + movementId));
        if (!movement.isManual()) {
            throw new IllegalStateException("Нельзя удалить движение, созданное автоматически заявкой на производство");
        }
        // Движения старого «Общего оприходования» (без документа формы, функция удалена 07.10)
        // сами себя в 1С не исправят. Строку документа формы удалить можно: документ
        // перепроводится в 1С без неё (см. OneCQueue).
        if (movement.getDocument() == null && movement.getOneCSyncedAt() != null) {
            throw new IllegalStateException("Уже выгружено в 1С документом №" + movement.getOneCDocumentNumber()
                    + " — сначала удалите его там");
        }
        StockDocument document = movement.getDocument();
        if (document != null) {
            document.getMovements().remove(movement);
            oneCQueue.stockDocument(document);
            stockDocumentRepository.save(document);
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
