package com.confectionery.crm.service;

import com.confectionery.crm.domain.*;
import com.confectionery.crm.repository.IngredientRepository;
import com.confectionery.crm.repository.ProductRepository;
import com.confectionery.crm.repository.ProductionRequestLineRepository;
import com.confectionery.crm.repository.ProductionRequestRepository;
import com.confectionery.crm.repository.RecipeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Заявка на сырьё под производство: одна заявка может покрывать сразу несколько товаров
 * (см. ProductionRequestLine) — например, из одного теста в один день делают несколько разных
 * упакованных вариантов. Создание считает состав по рецепту каждого товара (Recipe/RecipeItem)
 * и СУММИРУЕТ сырьё по ингредиенту в один общий список; сборка списывает сырьё со склада
 * (StockService.writeOffForProduction) один раз на всю заявку; оприходование — отдельно по
 * каждой строке (StockService.receiveProductForProduction), т.к. фактический выход у каждого
 * товара свой. Эти движения намеренно не "ручные" (см. StockMovement.manual) — они не должны
 * дублироваться на страницах "Поступления"/"Списание", свой список сырья уже виден в заявке.
 */
@Service
@RequiredArgsConstructor
public class ProductionRequestService {

    private final ProductionRequestRepository requestRepository;
    private final ProductionRequestLineRepository lineRepository;
    private final RecipeRepository recipeRepository;
    private final ProductService productService;
    private final StockService stockService;
    private final NotificationService notificationService;
    private final OneCProductionSyncService oneCProductionSyncService;
    private final IngredientRepository ingredientRepository;
    private final ProductRepository productRepository;

    /** Одна позиция при создании заявки: конкретный товар + сколько замесов его рецепта. */
    public record Line(Long productId, BigDecimal batches) {
    }

    /**
     * Одна строка дневного отчёта по оприходованию — см. {@link #dailyReport}. batches/norm —
     * план (сколько замесов сделали и сколько это должно было дать по рецепту), received —
     * факт оприходования, deviation = received - norm (может быть и отрицательным — выход
     * обычно "гуляет", это и есть повод показать отклонение).
     */
    public record ReportLine(String productName, String unit, BigDecimal batches, BigDecimal norm,
                              BigDecimal received, BigDecimal deviation, int receivedCount) {
    }

    private static final String NO_GROUP = "Без группы";

    /**
     * Позиции разбиваются по Product.group (см. Product.java) — на каждую группу создаётся
     * своя заявка через {@link #create}, название заявки = название группы. Товары без группы
     * уходят в одну общую заявку "Без группы". Один self-invocation-вызов create(...) на группу,
     * весь метод @Transactional — либо создаются все заявки сразу, либо ни одной.
     */
    @Transactional
    public List<ProductionRequest> createGrouped(List<Line> lines, String createdBy) {
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("Добавьте хотя бы одну позицию");
        }
        Map<String, List<Line>> byGroup = new LinkedHashMap<>();
        for (Line line : lines) {
            Product product = productService.findById(line.productId());
            String group = (product.getGroup() == null || product.getGroup().isBlank()) ? NO_GROUP : product.getGroup();
            byGroup.computeIfAbsent(group, g -> new ArrayList<>()).add(line);
        }
        List<ProductionRequest> created = new ArrayList<>();
        for (Map.Entry<String, List<Line>> entry : byGroup.entrySet()) {
            created.add(create(entry.getKey(), entry.getValue(), createdBy));
        }
        return created;
    }

    public List<Recipe> recipesWithProducts() {
        return recipeRepository.findAll();
    }

    /** Порядок статусов в списке заявок: сначала то, что ещё требует действия, затем закрытое. */
    private static final List<ProductionRequestStatus> STATUS_ORDER = List.of(
            ProductionRequestStatus.NEW, ProductionRequestStatus.COLLECTED,
            ProductionRequestStatus.RECEIVED, ProductionRequestStatus.CANCELLED);

    /**
     * Новые заявки — первыми, затем собранные, затем оприходованные (отменённые — последними);
     * внутри одного статуса — по дате создания, от новых к старым. Stream.sorted стабилен, а
     * repository уже отдаёт createdAt DESC — поэтому достаточно отсортировать только по рангу
     * статуса, порядок внутри группы сохранится сам.
     */
    public List<ProductionRequest> findAll() {
        return requestRepository.findAllByOrderByCreatedAtDesc().stream()
                .sorted(Comparator.comparingInt(r -> STATUS_ORDER.indexOf(r.getStatus())))
                .toList();
    }

    /** Счётчики для дашборда зав. склада сырья — см. {@link #dashboardSummary}. */
    public record DashboardCounts(long pending, long awaitingReceipt, long createdInRange, long collectedInRange) {
    }

    public record DashboardSummary(DashboardCounts counts, List<ProductionRequest> pendingRequests) {
    }

    /**
     * Сводка для дашборда роли WAREHOUSE_RAW (см. DashboardController/DashboardApiController):
     * pending — заявки в статусе «Новая», ждут сборки этой ролью (текущий бэклог, не привязан
     * к периоду — как inProduction/pendingTasks на дашборде ADMIN); awaitingReceipt — уже
     * собраны, ждут оприходования зав. складом ГП; createdInRange/collectedInRange — созданы/
     * собраны именно в выбранном периоде [rangeStart, rangeEnd). Один findAll(), чтобы не бить
     * базу дважды.
     */
    public DashboardSummary dashboardSummary(LocalDateTime rangeStart, LocalDateTime rangeEnd, int pendingLimit) {
        List<ProductionRequest> all = findAll();
        long pending = all.stream().filter(r -> r.getStatus() == ProductionRequestStatus.NEW).count();
        long awaitingReceipt = all.stream().filter(r -> r.getStatus() == ProductionRequestStatus.COLLECTED).count();
        long createdInRange = all.stream()
                .filter(r -> !r.getCreatedAt().isBefore(rangeStart) && r.getCreatedAt().isBefore(rangeEnd))
                .count();
        long collectedInRange = all.stream()
                .filter(r -> r.getCollectedAt() != null
                        && !r.getCollectedAt().isBefore(rangeStart) && r.getCollectedAt().isBefore(rangeEnd))
                .count();
        List<ProductionRequest> pendingRequests = all.stream()
                .filter(r -> r.getStatus() == ProductionRequestStatus.NEW)
                .limit(pendingLimit)
                .toList();
        return new DashboardSummary(new DashboardCounts(pending, awaitingReceipt, createdInRange, collectedInRange), pendingRequests);
    }

    public ProductionRequest findById(Long id) {
        return requestRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Заявка не найдена: " + id));
    }

    /**
     * @param title название заявки — задаёт пользователь (например, "Коржик"), пусто не бывает
     *              благодаря валидации на уровне контроллера/формы.
     */
    @Transactional
    public ProductionRequest create(String title, List<Line> lines, String createdBy) {
        ProductionRequest request = new ProductionRequest();
        request.setTitle(title);
        request.setCreatedBy(createdBy);
        populate(request, lines);

        request = requestRepository.save(request);
        notifyCreated(request);
        return request;
    }

    /**
     * Редактирование ещё не собранной заявки — позиции и просуммированное сырьё пересчитываются
     * заново с нуля (как при создании), название заявки не меняется (группа определяется один
     * раз при создании, см. {@link #createGrouped}). После сборки (списание сырья со склада уже
     * произошло) редактировать нельзя — иначе список сырья разъедется с тем, что реально списано.
     */
    @Transactional
    public ProductionRequest update(Long requestId, List<Line> lines, String updatedBy) {
        ProductionRequest request = findById(requestId);
        if (request.getStatus() != ProductionRequestStatus.NEW) {
            throw new IllegalStateException("Редактировать можно только заявку в статусе «Новая»");
        }
        request.getLines().clear();
        request.getItems().clear();
        populate(request, lines);
        notifyUpdated(request, updatedBy);
        return request;
    }

    /** Общая логика пересчёта строк/сырья заявки — используется и при создании, и при редактировании. */
    private void populate(ProductionRequest request, List<Line> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("Добавьте хотя бы одну позицию");
        }
        Map<Ingredient, BigDecimal> combined = new LinkedHashMap<>();
        for (Line line : lines) {
            if (line.batches() == null || line.batches().signum() <= 0) {
                throw new IllegalArgumentException("Количество замесов должно быть больше нуля");
            }
            Product product = productService.findById(line.productId());
            Recipe recipe = recipeRepository.findByProductId(line.productId())
                    .orElseThrow(() -> new IllegalArgumentException("У товара «" + product.getName() + "» нет рецептуры"));

            ProductionRequestLine requestLine = new ProductionRequestLine();
            requestLine.setProduct(product);
            requestLine.setBatches(line.batches());
            requestLine.setPlannedQuantity(line.batches().multiply(recipe.getYieldQuantity(), MathContext.DECIMAL64));
            request.addLine(requestLine);

            for (RecipeItem recipeItem : recipe.getItems()) {
                BigDecimal toAdd = recipeItem.getQuantity().multiply(line.batches(), MathContext.DECIMAL64);
                combined.merge(recipeItem.getIngredient(), toAdd, BigDecimal::add);
            }
        }
        for (Map.Entry<Ingredient, BigDecimal> entry : combined.entrySet()) {
            ProductionRequestItem item = new ProductionRequestItem();
            item.setIngredient(entry.getKey());
            item.setRequiredQuantity(entry.getValue());
            request.addItem(item);
        }
    }

    /**
     * Отмена ещё не собранной заявки — сырьё со склада ещё не списано (это происходит только
     * при {@link #collect}), поэтому отмена не требует никаких обратных складских движений.
     */
    @Transactional
    public ProductionRequest cancel(Long requestId, String by) {
        ProductionRequest request = findById(requestId);
        if (request.getStatus() != ProductionRequestStatus.NEW) {
            throw new IllegalStateException("Отменить можно только заявку в статусе «Новая»");
        }
        request.setStatus(ProductionRequestStatus.CANCELLED);
        String message = "Заявка №" + request.getId() + " «" + request.getTitle() + "» отменена (" + by + ")";
        notificationService.notifyRole(Role.ADMIN, message, null);
        notificationService.notifyRole(Role.ACCOUNTANT, message, null);
        notificationService.notifyRole(Role.WAREHOUSE_RAW, message, null);
        return request;
    }

    /**
     * Полное удаление заявки (в отличие от {@link #cancel}, которая работает только для
     * статуса «Новая» и не трогает склад) — можно удалить заявку в ЛЮБОМ статусе, включая уже
     * собранную/оприходованную: откатывает всё, что она успела сделать со складом (возвращает
     * списанное сырьё, вычитает оприходованную продукцию обратно), и если по ней уже
     * создавались документы в 1С (см. OneCProductionSyncService) — помечает их там на
     * удаление (best-effort, не блокирует удаление в CRM, если 1С недоступна). Только ADMIN —
     * необратимо и затрагивает реальные остатки.
     */
    @Transactional
    public void delete(Long requestId) {
        ProductionRequest request = findById(requestId);

        boolean materialsWrittenOff = request.getStatus() == ProductionRequestStatus.COLLECTED
                || request.getStatus() == ProductionRequestStatus.RECEIVED;
        if (materialsWrittenOff) {
            for (ProductionRequestItem item : request.getItems()) {
                Ingredient ingredient = item.getIngredient();
                ingredient.setQuantityInStock(ingredient.getQuantityInStock().add(item.getRequiredQuantity()));
                ingredientRepository.save(ingredient);
            }
        }
        for (ProductionRequestLine line : request.getLines()) {
            if (line.isReceived()) {
                Product product = line.getProduct();
                product.setQuantityInStock(product.getQuantityInStock().subtract(line.getReceivedQuantity()));
                productRepository.save(product);
            }
        }

        oneCProductionSyncService.tryMarkForDeletion(request);

        String message = "Заявка №" + request.getId() + " «" + request.getTitle() + "» удалена";
        notificationService.notifyRole(Role.ADMIN, message, null);
        notificationService.notifyRole(Role.ACCOUNTANT, message, null);
        requestRepository.delete(request);
    }

    /**
     * Замена сырья в позиции заявки — например, нужного не хватило на складе и вместо него
     * решили выдать другое. Доступно, только пока заявка ещё не собрана (сырьё физически ещё
     * не списано — после сборки менять позицию задним числом нельзя, это разошлось бы с уже
     * произошедшим списанием). Количество можно поменять вместе с сырьём (замена не всегда
     * 1:1), если не передано — остаётся прежним. Настоящее название сырья по рецепту
     * сохраняется в {@link ProductionRequestItem#getOriginalIngredientName()} — видно в
     * заявке, что здесь была замена.
     */
    @Transactional
    public ProductionRequest substituteItem(Long requestId, Long itemId, Long newIngredientId,
                                             BigDecimal newQuantity, String by) {
        ProductionRequest request = findById(requestId);
        if (request.getStatus() != ProductionRequestStatus.NEW) {
            throw new IllegalStateException("Заменить сырьё можно только в ещё не собранной заявке");
        }
        ProductionRequestItem item = request.getItems().stream()
                .filter(i -> i.getId().equals(itemId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Позиция заявки не найдена: " + itemId));
        Ingredient newIngredient = ingredientRepository.findById(newIngredientId)
                .orElseThrow(() -> new IllegalArgumentException("Сырьё не найдено: " + newIngredientId));

        String oldName = item.getIngredient().getName();
        BigDecimal oldQuantity = item.getRequiredQuantity();
        if (item.getOriginalIngredientName() == null) {
            item.setOriginalIngredientName(oldName);
        }
        item.setIngredient(newIngredient);
        if (newQuantity != null) {
            if (newQuantity.signum() <= 0) {
                throw new IllegalArgumentException("Количество должно быть больше нуля");
            }
            item.setRequiredQuantity(newQuantity);
        }

        String message = "Заявка №" + request.getId() + " «" + request.getTitle() + "»: замена сырья — "
                + oldName + " (" + oldQuantity.stripTrailingZeros().toPlainString() + ") заменено на "
                + newIngredient.getName() + " (" + item.getRequiredQuantity().stripTrailingZeros().toPlainString() + ") — " + by;
        notificationService.notifyRole(Role.ADMIN, message, null);
        notificationService.notifyRole(Role.ACCOUNTANT, message, null);
        notificationService.notifyRole(Role.WAREHOUSE_RAW, message, null);
        return request;
    }

    private void notifyCreated(ProductionRequest request) {
        BigDecimal totalBatches = request.getLines().stream()
                .map(ProductionRequestLine::getBatches).reduce(BigDecimal.ZERO, BigDecimal::add);
        String lineSummary = request.getLines().stream()
                .map(l -> l.getProduct().getName() + " — " + l.getBatches().stripTrailingZeros().toPlainString() + " замес.")
                .collect(Collectors.joining(", "));
        String message = "Новая заявка №" + request.getId() + " «" + request.getTitle() + "»: "
                + totalBatches.stripTrailingZeros().toPlainString() + " замес. всего (" + lineSummary + ")";
        notificationService.notifyRole(Role.ADMIN, message, null);
        notificationService.notifyRole(Role.ACCOUNTANT, message, null);
        notificationService.notifyRole(Role.WAREHOUSE_RAW, message, null);
    }

    private void notifyUpdated(ProductionRequest request, String by) {
        BigDecimal totalBatches = request.getLines().stream()
                .map(ProductionRequestLine::getBatches).reduce(BigDecimal.ZERO, BigDecimal::add);
        String lineSummary = request.getLines().stream()
                .map(l -> l.getProduct().getName() + " — " + l.getBatches().stripTrailingZeros().toPlainString() + " замес.")
                .collect(Collectors.joining(", "));
        String message = "Заявка №" + request.getId() + " «" + request.getTitle() + "» изменена (" + by + "): "
                + totalBatches.stripTrailingZeros().toPlainString() + " замес. всего (" + lineSummary + ")";
        notificationService.notifyRole(Role.ADMIN, message, null);
        notificationService.notifyRole(Role.ACCOUNTANT, message, null);
        notificationService.notifyRole(Role.WAREHOUSE_RAW, message, null);
    }

    /**
     * Зав. склада сырья физически собрал все позиции по общему чек-листу и нажал "Собрано" —
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
            String reason = "Заявка №" + request.getId() + " «" + request.getTitle() + "»";
            stockService.writeOffForProduction(item.getIngredient(), item.getRequiredQuantity(), reason, by);
        }
        request.setStatus(ProductionRequestStatus.COLLECTED);
        request.setCollectedBy(by);
        request.setCollectedAt(LocalDateTime.now());

        String message = "Заявка №" + request.getId() + " «" + request.getTitle() + "» собрана — ждёт оприходования";
        notificationService.notifyRole(Role.ADMIN, message, null);
        notificationService.notifyRole(Role.WAREHOUSE_FG, message, null);
        return request;
    }

    /**
     * Зав. склада ГП вводит фактически полученное количество по ОДНОЙ строке заявки — это и
     * есть оприходование. Когда оприходованы все строки, вся заявка помечается RECEIVED.
     */
    @Transactional
    public ProductionRequest receiveLine(Long requestId, Long lineId, BigDecimal receivedQuantity, String by) {
        ProductionRequest request = findById(requestId);
        if (request.getStatus() != ProductionRequestStatus.COLLECTED) {
            throw new IllegalStateException("Оприходовать можно только собранную заявку");
        }
        ProductionRequestLine line = request.getLines().stream()
                .filter(l -> l.getId().equals(lineId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Позиция заявки не найдена: " + lineId));
        if (line.isReceived()) {
            throw new IllegalStateException("Эта позиция уже оприходована");
        }
        if (receivedQuantity == null || receivedQuantity.signum() <= 0) {
            throw new IllegalArgumentException("Количество должно быть больше нуля");
        }
        String reason = "Заявка №" + request.getId() + " «" + request.getTitle() + "»";
        stockService.receiveProductForProduction(line.getProduct(), receivedQuantity, reason, by);

        line.setReceivedBy(by);
        line.setReceivedAt(LocalDateTime.now());
        line.setReceivedQuantity(receivedQuantity);

        boolean allReceived = request.getLines().stream().allMatch(ProductionRequestLine::isReceived);
        if (allReceived) {
            request.setStatus(ProductionRequestStatus.RECEIVED);
        }

        String receivedMessage = "Заявка №" + request.getId() + " «" + request.getTitle()
                + "»: оприходовано " + receivedQuantity.stripTrailingZeros().toPlainString() + " " + safeUnit(line.getProduct())
                + " «" + line.getProduct().getName() + "»"
                + (allReceived ? " — заявка выполнена полностью" : "");
        notificationService.notifyRole(Role.ADMIN, receivedMessage, null);
        notificationService.notifyRole(Role.ACCOUNTANT, receivedMessage, null);

        if (allReceived) {
            oneCProductionSyncService.trySyncRequest(request);
        }
        return request;
    }

    /**
     * Ручная повторная попытка выгрузить оприходованную заявку в 1С — на случай, если
     * автоматическая (см. receiveLine выше) не сработала (например, backend был поднят без
     * переменных окружения 1С) или закончилась ошибкой в первый раз.
     */
    @Transactional
    public ProductionRequest resyncOneC(Long requestId) {
        ProductionRequest request = findById(requestId);
        if (request.getStatus() != ProductionRequestStatus.RECEIVED) {
            throw new IllegalStateException("Заявка ещё не оприходована полностью");
        }
        if (request.getOneCReportNumber() != null) {
            throw new IllegalStateException("Заявка уже выгружена в 1С");
        }
        request.setOneCSyncError(null);
        oneCProductionSyncService.trySyncRequest(request);
        request = requestRepository.save(request);
        if (request.getOneCReportNumber() == null) {
            throw new IllegalStateException(request.getOneCSyncError() != null
                    ? request.getOneCSyncError() : "1С недоступна или не настроена");
        }
        return request;
    }

    /**
     * Сколько готовой продукции реально оприходовано за календарный день — по всем заявкам,
     * сгруппировано по товару (в заявке "Коржик" может быть 3 строки на 3 разных товара —
     * здесь они не смешиваются, каждый товар своей строкой). Оприходование конкретной строки
     * может случиться позже создания заявки (например, заявка собрана вечером, оприходована
     * утром) — отчёт считает именно по дате ОПРИХОДОВАНИЯ (receivedAt), не по дате заявки.
     */
    public List<ReportLine> dailyReport(LocalDate date) {
        List<ProductionRequestLine> lines = lineRepository.findByReceivedAtBetweenOrderByReceivedAtAsc(
                date.atStartOfDay(), date.plusDays(1).atStartOfDay());

        Map<Product, BigDecimal> batchesByProduct = new LinkedHashMap<>();
        Map<Product, BigDecimal> normByProduct = new LinkedHashMap<>();
        Map<Product, BigDecimal> receivedByProduct = new LinkedHashMap<>();
        Map<Product, Integer> countByProduct = new LinkedHashMap<>();
        for (ProductionRequestLine line : lines) {
            batchesByProduct.merge(line.getProduct(), line.getBatches(), BigDecimal::add);
            normByProduct.merge(line.getProduct(), line.getPlannedQuantity(), BigDecimal::add);
            receivedByProduct.merge(line.getProduct(), line.getReceivedQuantity(), BigDecimal::add);
            countByProduct.merge(line.getProduct(), 1, Integer::sum);
        }
        return receivedByProduct.entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getKey().getName()))
                .map(e -> {
                    Product product = e.getKey();
                    BigDecimal norm = normByProduct.get(product);
                    BigDecimal received = e.getValue();
                    return new ReportLine(product.getName(), safeUnit(product), batchesByProduct.get(product), norm,
                            received, received.subtract(norm), countByProduct.get(product));
                })
                .toList();
    }

    private String safeUnit(Product product) {
        return product.getUnit() == null || product.getUnit().isBlank() ? "шт" : product.getUnit();
    }
}
