package com.confectionery.crm.service;

import com.confectionery.crm.domain.*;
import com.confectionery.crm.repository.ProductionRequestRepository;
import com.confectionery.crm.repository.RecipeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Заявка бухгалтера на сырьё под производство: создание считает состав по рецепту товара
 * (см. Recipe/RecipeItem), сборка списывает сырьё со склада (StockService.writeOff),
 * оприходование увеличивает остаток готовой продукции (StockService.receiveProduct) —
 * никакой новой логики движения остатков, только переиспользование уже проверенной.
 */
@Service
@RequiredArgsConstructor
public class ProductionRequestService {

    private final ProductionRequestRepository requestRepository;
    private final RecipeRepository recipeRepository;
    private final ProductService productService;
    private final StockService stockService;
    private final NotificationService notificationService;

    public List<Product> productsWithRecipe() {
        return recipeRepository.findAll().stream().map(Recipe::getProduct).toList();
    }

    public List<ProductionRequest> findAll() {
        return requestRepository.findAllByOrderByCreatedAtDesc();
    }

    public ProductionRequest findById(Long id) {
        return requestRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Заявка не найдена: " + id));
    }

    @Transactional
    public ProductionRequest create(Long productId, BigDecimal batches, String createdBy) {
        if (batches == null || batches.signum() <= 0) {
            throw new IllegalArgumentException("Количество замесов должно быть больше нуля");
        }
        Product product = productService.findById(productId);
        Recipe recipe = recipeRepository.findByProductId(productId)
                .orElseThrow(() -> new IllegalArgumentException("У товара «" + product.getName() + "» нет рецептуры"));

        ProductionRequest request = new ProductionRequest();
        request.setProduct(product);
        request.setBatches(batches);
        request.setCreatedBy(createdBy);
        for (RecipeItem recipeItem : recipe.getItems()) {
            ProductionRequestItem item = new ProductionRequestItem();
            item.setIngredient(recipeItem.getIngredient());
            item.setRequiredQuantity(recipeItem.getQuantity().multiply(batches));
            request.addItem(item);
        }
        request = requestRepository.save(request);

        String message = "Новая заявка №" + request.getId() + " на производство «" + product.getName()
                + "» (" + batches.stripTrailingZeros().toPlainString() + " замес.)";
        notificationService.notifyRole(Role.ADMIN, message, null);
        notificationService.notifyRole(Role.WAREHOUSE_RAW, message, null);
        return request;
    }

    /**
     * Зав. склада сырья физически собрал все позиции по чек-листу и нажал "Собрано" —
     * confirmedItemIds должен покрывать ВСЕ позиции заявки (проверка от того, что кладовщик
     * забудет/перепутает позицию), после чего сырьё списывается со склада.
     */
    @Transactional
    public ProductionRequest collect(Long requestId, Set<Long> confirmedItemIds, String by) {
        ProductionRequest request = findById(requestId);
        if (request.getStatus() != ProductionRequestStatus.NEW) {
            throw new IllegalStateException("Заявку можно собрать только в статусе «Новая»");
        }
        Set<Long> allItemIds = request.getItems().stream().map(ProductionRequestItem::getId).collect(Collectors.toSet());
        if (!confirmedItemIds.containsAll(allItemIds)) {
            throw new IllegalArgumentException("Отметьте все позиции заявки перед сборкой");
        }
        for (ProductionRequestItem item : request.getItems()) {
            String reason = "Заявка №" + request.getId() + " на производство «" + request.getProduct().getName() + "»";
            stockService.writeOff(item.getIngredient(), item.getRequiredQuantity(), reason, by);
        }
        request.setStatus(ProductionRequestStatus.COLLECTED);
        request.setCollectedBy(by);
        request.setCollectedAt(LocalDateTime.now());

        String message = "Заявка №" + request.getId() + " собрана — ждёт оприходования «"
                + request.getProduct().getName() + "»";
        notificationService.notifyRole(Role.ADMIN, message, null);
        notificationService.notifyRole(Role.WAREHOUSE_FG, message, null);
        return request;
    }

    /** Зав. склада ГП вводит фактически полученное количество готовой продукции — это и есть оприходование. */
    @Transactional
    public ProductionRequest receive(Long requestId, BigDecimal receivedQuantity, String by) {
        ProductionRequest request = findById(requestId);
        if (request.getStatus() != ProductionRequestStatus.COLLECTED) {
            throw new IllegalStateException("Оприходовать можно только собранную заявку");
        }
        if (receivedQuantity == null || receivedQuantity.signum() <= 0) {
            throw new IllegalArgumentException("Количество должно быть больше нуля");
        }
        String reason = "Заявка №" + request.getId() + " на производство";
        stockService.receiveProduct(request.getProduct(), receivedQuantity, reason, by);

        request.setStatus(ProductionRequestStatus.RECEIVED);
        request.setReceivedBy(by);
        request.setReceivedAt(LocalDateTime.now());
        request.setReceivedQuantity(receivedQuantity);

        notificationService.notifyRole(Role.ADMIN, "Заявка №" + request.getId() + " выполнена: оприходовано "
                + receivedQuantity.stripTrailingZeros().toPlainString() + " " + safeUnit(request.getProduct())
                + " «" + request.getProduct().getName() + "»", null);
        return request;
    }

    private String safeUnit(Product product) {
        return product.getUnit() == null || product.getUnit().isBlank() ? "шт" : product.getUnit();
    }
}
