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
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
     * Одна строка отчёта по производству — см. {@link #report}. batches/norm —
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
     * @param documentDate дата документа для 1С (см. {@link ProductionRequest#getDocumentDate()});
     *                      null — сегодня.
     */
    @Transactional
    public List<ProductionRequest> createGrouped(List<Line> lines, String createdBy, LocalDate documentDate) {
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
            created.add(create(entry.getKey(), entry.getValue(), createdBy, documentDate));
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
     * Сверка нормы по ОБЩЕЙ МАССЕ вместо штук — для товаров вроде Чак-чака, где несколько
     * фасовок (Б/мини/резанный/резанный мини) режутся из одного замеса: если рабочие перепутали
     * пропорции при нарезке, построчная проверка (см. {@link ProductionRequestLine#isInsufficient()})
     * ошибочно покажет недостачу по одной позиции и избыток по другой, хотя по массе сырья всё
     * в порядке. Считает только по строкам, где у товара задан {@link Product#getNetWeightGrams()}
     * (см. заполнение поля в карточке товара) — остальные строки в сумму не попадают и
     * продолжают сверяться по старой построчной логике. {@code deviationGrams} = факт − норма;
     * отрицательное значение — по массе не хватает.
     * @return null, если весом отмечено меньше двух позиций заявки — тогда построчной проверки
     * достаточно, сводка по массе не добавляет информации.
     * Вызывается и из web-контроллера, и из {@link com.confectionery.crm.api.dto.ProductionRequestDto#from}
     * (передаёт готовый результат явно — DTO не имеет доступа к сервисам) для мобильного API.
     */
    public MassSummary massSummary(ProductionRequest request) {
        BigDecimal plannedGrams = BigDecimal.ZERO;
        BigDecimal actualGrams = BigDecimal.ZERO;
        int weightedLines = 0;
        boolean allWeightedReceived = true;
        // Самый крупный по весу вариант СРЕДИ ТОГО, ЧТО ЕСТЬ В ЗАЯВКЕ — запасной вариант единицы
        // пересчёта отклонения массы в штуки, если ни один товар группы не отмечен явным
        // эталоном (см. ниже) — например, заявка не включает сам эталонный товар.
        String fallbackReferenceName = null;
        BigDecimal fallbackReferenceWeight = null;
        String group = null;
        for (ProductionRequestLine line : request.getLines()) {
            group = line.getProduct().getGroup();
            BigDecimal weight = line.getProduct().getNetWeightGrams();
            if (weight == null) {
                continue;
            }
            weightedLines++;
            if (fallbackReferenceWeight == null || weight.compareTo(fallbackReferenceWeight) > 0) {
                fallbackReferenceWeight = weight;
                fallbackReferenceName = line.getProduct().getName();
            }
            if (line.getPlannedQuantity() != null) {
                plannedGrams = plannedGrams.add(line.getPlannedQuantity().multiply(weight, MathContext.DECIMAL64));
            }
            if (line.isReceived()) {
                actualGrams = actualGrams.add(line.getReceivedQuantity().multiply(weight, MathContext.DECIMAL64));
            } else {
                allWeightedReceived = false;
            }
        }
        if (weightedLines < 2) {
            return null;
        }

        String referenceProductName = fallbackReferenceName;
        BigDecimal referenceWeight = fallbackReferenceWeight;
        if (group != null) {
            Product explicitReference = productRepository.findFirstByGroupAndMassReferenceProductTrue(group).orElse(null);
            if (explicitReference != null && explicitReference.getNetWeightGrams() != null) {
                referenceProductName = explicitReference.getName();
                referenceWeight = explicitReference.getNetWeightGrams();
            }
        }
        return new MassSummary(plannedGrams, actualGrams, allWeightedReceived, referenceProductName, referenceWeight);
    }

    public record MassSummary(BigDecimal plannedGrams, BigDecimal actualGrams, boolean allWeightedReceived,
                               String referenceProductName, BigDecimal referenceWeightGrams) {
        private static final BigDecimal GRAMS_PER_KG = BigDecimal.valueOf(1000);

        public BigDecimal getDeviationGrams() {
            return actualGrams.subtract(plannedGrams);
        }

        public boolean isInsufficient() {
            return getDeviationGrams().signum() < 0;
        }

        public BigDecimal getPlannedKg() {
            return plannedGrams.divide(GRAMS_PER_KG, 3, RoundingMode.HALF_UP);
        }

        public BigDecimal getActualKg() {
            return actualGrams.divide(GRAMS_PER_KG, 3, RoundingMode.HALF_UP);
        }

        /** Отклонение в кг со знаком: факт − норма (отрицательное — не хватает). */
        public BigDecimal getDeviationKg() {
            return getDeviationGrams().divide(GRAMS_PER_KG, 3, RoundingMode.HALF_UP);
        }

        /** Модуль отклонения в кг — знак (не хватает/с запасом) показывается отдельно в UI. */
        public BigDecimal getDeviationKgAbs() {
            return getDeviationGrams().abs().divide(GRAMS_PER_KG, 3, RoundingMode.HALF_UP);
        }

        /**
         * Модуль отклонения, пересчитанный в штуки {@link #referenceProductName} (самого
         * крупного варианта группы) — "не хватает N шт. Чак Чак Б", а не просто "N кг сырья".
         * Условная величина — реальная недостача может закрываться любым сочетанием фасовок,
         * это просто наглядный ориентир для кладовщика.
         */
        public BigDecimal getDeviationPiecesAbs() {
            return getDeviationGrams().abs().divide(referenceWeightGrams, 1, RoundingMode.HALF_UP);
        }
    }

    /**
     * @param title название заявки — задаёт пользователь (например, "Коржик"), пусто не бывает
     *              благодаря валидации на уровне контроллера/формы.
     * @param documentDate дата документа для 1С; null — сегодня (см. {@link ProductionRequest#getDocumentDate()}).
     */
    @Transactional
    public ProductionRequest create(String title, List<Line> lines, String createdBy, LocalDate documentDate) {
        ProductionRequest request = new ProductionRequest();
        request.setTitle(title);
        request.setCreatedBy(createdBy);
        request.setDocumentDate(documentDate != null ? documentDate : LocalDate.now());
        populate(request, lines);

        request = requestRepository.save(request);
        notifyCreated(request);
        oneCProductionSyncService.trySyncRequest(request);
        return request;
    }

    /**
     * Редактирование позиций (товар/замесов) — позиции и просуммированное сырьё пересчитываются
     * заново с нуля (как при создании), название заявки не меняется (группа определяется один
     * раз при создании, см. {@link #createGrouped}). Доступно и после сборки ({@link
     * ProductionRequestStatus#COLLECTED}) — по просьбе пользователя (склад может ошибиться в
     * замесах уже после того, как нажал «Собрано»); сырьё к этому моменту уже физически списано
     * со склада в {@link #collect}, поэтому разница между старым и новым составом сырья здесь
     * ЖИВОЕ движение по складу — та же логика, что и в {@link #substituteItem}. Если по заявке
     * уже есть оприходованная продукция ({@link ProductionRequestLine#isReceived()}) — править
     * позиции нельзя, иначе бесследно исчезнет факт оприходования и разъедется остаток товара на
     * складе; как только заявка полностью оприходована ({@link ProductionRequestStatus#RECEIVED})
     * или завершена — тоже нельзя, дальше состав меняют только через позиции сырья
     * ({@link #substituteItem}/{@link #addItem}/{@link #removeItem}).
     * @param documentDate дата документа для 1С; null — не менять текущую.
     */
    @Transactional
    public ProductionRequest update(Long requestId, List<Line> lines, String updatedBy, LocalDate documentDate) {
        ProductionRequest request = findById(requestId);
        if (request.getStatus() == ProductionRequestStatus.CANCELLED
                || request.getStatus() == ProductionRequestStatus.RECEIVED) {
            throw new IllegalStateException("Редактировать позиции можно только до полного оприходования заявки");
        }
        if (request.isRequisitionCompleted()) {
            throw new IllegalStateException("Заявка завершена — сначала отмените завершение");
        }
        boolean stockAlreadyWrittenOff = request.getStatus() == ProductionRequestStatus.COLLECTED;
        if (stockAlreadyWrittenOff && request.getLines().stream().anyMatch(ProductionRequestLine::isReceived)) {
            throw new IllegalStateException("Нельзя менять позиции — по заявке уже есть оприходованная продукция");
        }
        if (documentDate != null) {
            request.setDocumentDate(documentDate);
        }

        Map<Long, BigDecimal> oldRequirements = new LinkedHashMap<>();
        Map<Long, Ingredient> ingredientsById = new LinkedHashMap<>();
        if (stockAlreadyWrittenOff) {
            for (ProductionRequestItem item : request.getItems()) {
                oldRequirements.merge(item.getIngredient().getId(), item.getRequiredQuantity(), BigDecimal::add);
                ingredientsById.put(item.getIngredient().getId(), item.getIngredient());
            }
        }

        request.getLines().clear();
        request.getItems().clear();
        populate(request, lines);

        if (stockAlreadyWrittenOff) {
            Map<Long, BigDecimal> newRequirements = new LinkedHashMap<>();
            for (ProductionRequestItem item : request.getItems()) {
                newRequirements.merge(item.getIngredient().getId(), item.getRequiredQuantity(), BigDecimal::add);
                ingredientsById.put(item.getIngredient().getId(), item.getIngredient());
            }
            String reason = "Заявка №" + request.getId() + " «" + request.getTitle() + "» — правка позиций";
            Set<Long> allIngredientIds = new LinkedHashSet<>();
            allIngredientIds.addAll(oldRequirements.keySet());
            allIngredientIds.addAll(newRequirements.keySet());
            for (Long ingredientId : allIngredientIds) {
                BigDecimal oldQty = oldRequirements.getOrDefault(ingredientId, BigDecimal.ZERO);
                BigDecimal newQty = newRequirements.getOrDefault(ingredientId, BigDecimal.ZERO);
                BigDecimal diff = newQty.subtract(oldQty);
                Ingredient ingredient = ingredientsById.get(ingredientId);
                if (diff.signum() > 0) {
                    stockService.writeOffForProduction(ingredient, diff, reason, updatedBy);
                } else if (diff.signum() < 0) {
                    stockService.receiveForProduction(ingredient, diff.negate(), reason, updatedBy);
                }
            }
        }

        oneCProductionSyncService.trySyncRequest(request);
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
            item.setRequiredQuantity(roundRequiredQuantity(entry.getKey(), entry.getValue()));
            request.addItem(item);
        }
    }

    /**
     * Штучное сырьё (упаковка вроде "гофрокоробка") в рецепте намеренно задаётся дробным
     * числом на один замес (например 3,5 шт/замес — чтобы после умножения на число замесов
     * получалась верная дробная потребность), но физически выдать со склада можно только целое
     * число штук — округляем ИТОГОВУЮ потребность вверх (116,6 -> 117), а не сам рецепт.
     * Весовое/объёмное сырьё (кг, г, л, мл) округления не требует.
     */
    private static BigDecimal roundRequiredQuantity(Ingredient ingredient, BigDecimal quantity) {
        String unit = ingredient.getUnit();
        if (unit != null && unit.trim().equalsIgnoreCase("шт")) {
            return quantity.setScale(0, RoundingMode.CEILING);
        }
        return quantity;
    }

    /**
     * Отмена заявки в ЛЮБОМ статусе, кроме уже отменённой (раньше отмена работала только для
     * статуса «Новая», а откат склада/удаление документов в 1С были отдельной кнопкой «Удалить
     * заявку» — теперь вся эта функциональность объединена здесь по просьбе пользователя, кнопка
     * «Удалить заявку» убрана из UI). Откатывает всё, что заявка успела сделать со складом
     * (возвращает списанное сырьё, вычитает оприходованную продукцию обратно), и если по ней уже
     * создавались документы в 1С (см. OneCProductionSyncService) — помечает их там на удаление
     * (best-effort, не блокирует отмену в CRM, если 1С недоступна). В отличие от прежнего
     * "Удалить" заявка НЕ стирается из БД — остаётся записью в статусе CANCELLED, видна в
     * истории/отчётах.
     */
    @Transactional
    public ProductionRequest cancel(Long requestId, String by) {
        ProductionRequest request = findById(requestId);
        if (request.getStatus() == ProductionRequestStatus.CANCELLED) {
            throw new IllegalStateException("Заявка уже отменена");
        }
        if (request.isRequisitionCompleted()) {
            throw new IllegalStateException("Заявка завершена — сначала отмените завершение");
        }

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

        request.setStatus(ProductionRequestStatus.CANCELLED);
        oneCProductionSyncService.tryMarkForDeletion(request);

        String message = "Заявка №" + request.getId() + " «" + request.getTitle() + "» отменена (" + by + ")";
        notificationService.notifyRole(Role.ADMIN, message, null);
        notificationService.notifyRole(Role.ACCOUNTANT, message, null);
        notificationService.notifyRole(Role.WAREHOUSE_RAW, message, null);
        return request;
    }

    /**
     * Замена сырья в позиции заявки — например, нужного не хватило на складе и вместо него
     * решили выдать другое, либо во время производства понадобилось больше исходного. Доступно
     * ADMIN и WAREHOUSE_RAW (см. @PreAuthorize в контроллерах) в ЛЮБОМ статусе заявки, кроме
     * отменённой — раньше это разрешалось только до сборки (сырьё ещё не списано), но по
     * просьбе бизнеса зав. складу сырья и админу нужно поправлять состав и во время
     * производства. Количество можно поменять вместе с сырьём (замена не всегда 1:1), если не
     * передано — остаётся прежним. Настоящее название сырья по рецепту сохраняется в
     * {@link ProductionRequestItem#getOriginalIngredientName()} — видно в заявке, что здесь
     * была замена.
     *
     * <p>Если заявка уже собрана ({@link ProductionRequestStatus#COLLECTED}/{@link
     * ProductionRequestStatus#RECEIVED}) — сырьё уже физически списано со склада в
     * {@link #collect}, поэтому любое изменение требуемого количества здесь ЖИВОЕ движение по
     * складу (см. {@link StockService#writeOffForProduction}/{@link StockService#receiveForProduction}):
     * увеличили — довписываем со склада, уменьшили или заменили сырьё — лишнее возвращается
     * обратно. Пока заявка ещё «Новая», склад этой правкой не трогаем — списание произойдёт
     * позже, при сборке, уже по новым цифрам.
     *
     * <p>{@code newIngredientId == null} — отдельный случай "изменить только количество, сырьё
     * не трогать" (в этом случае {@code newQuantity} обязателен): без этого пользователю
     * пришлось бы формально выбирать какое-то другое сырьё из списка, только чтобы поправить
     * цифру в уже вычисленной позиции.
     */
    @Transactional
    public ProductionRequest substituteItem(Long requestId, Long itemId, Long newIngredientId,
                                             BigDecimal newQuantity, String by) {
        ProductionRequest request = findById(requestId);
        if (request.getStatus() == ProductionRequestStatus.CANCELLED) {
            throw new IllegalStateException("Нельзя менять состав отменённой заявки");
        }
        if (request.isRequisitionCompleted()) {
            throw new IllegalStateException("Заявка завершена — сначала отмените завершение");
        }
        boolean stockAlreadyWrittenOff = request.getStatus() == ProductionRequestStatus.COLLECTED
                || request.getStatus() == ProductionRequestStatus.RECEIVED;
        ProductionRequestItem item = request.getItems().stream()
                .filter(i -> i.getId().equals(itemId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Позиция заявки не найдена: " + itemId));
        String reason = "Заявка №" + request.getId() + " «" + request.getTitle() + "» — правка состава";

        // Без push-уведомлений — по просьбе бизнеса: во время производства состав может
        // правиться сколько угодно раз, спамить на каждую правку не нужно (см. notifyReceivedSummary
        // и completeRequisition — там одна сводная нотификация в конце, этого достаточно).
        if (newIngredientId == null) {
            if (newQuantity == null) {
                throw new IllegalArgumentException("Укажите новое количество");
            }
            if (newQuantity.signum() <= 0) {
                throw new IllegalArgumentException("Количество должно быть больше нуля");
            }
            BigDecimal oldQuantity = item.getRequiredQuantity();
            if (stockAlreadyWrittenOff) {
                BigDecimal diff = newQuantity.subtract(oldQuantity);
                if (diff.signum() > 0) {
                    stockService.writeOffForProduction(item.getIngredient(), diff, reason, by);
                } else if (diff.signum() < 0) {
                    stockService.receiveForProduction(item.getIngredient(), diff.negate(), reason, by);
                }
            }
            item.setRequiredQuantity(newQuantity);
        } else {
            Ingredient newIngredient = ingredientRepository.findById(newIngredientId)
                    .orElseThrow(() -> new IllegalArgumentException("Сырьё не найдено: " + newIngredientId));

            Ingredient oldIngredient = item.getIngredient();
            String oldName = oldIngredient.getName();
            BigDecimal oldQuantity = item.getRequiredQuantity();
            BigDecimal newQty = newQuantity != null ? newQuantity : oldQuantity;
            if (newQty.signum() <= 0) {
                throw new IllegalArgumentException("Количество должно быть больше нуля");
            }
            if (stockAlreadyWrittenOff) {
                // Возвращаем на склад ранее списанное старое сырьё и списываем новое — заявка
                // уже собрана, значит физическая замена происходит прямо сейчас.
                stockService.receiveForProduction(oldIngredient, oldQuantity, reason, by);
                stockService.writeOffForProduction(newIngredient, newQty, reason, by);
            }
            if (item.getOriginalIngredientName() == null) {
                item.setOriginalIngredientName(oldName);
            }
            item.setIngredient(newIngredient);
            item.setRequiredQuantity(newQty);
        }

        oneCProductionSyncService.trySyncRequest(request);
        return request;
    }

    /**
     * Добавляет в заявку СОВСЕМ НОВУЮ позицию сырья — которого не было в исходном рецепте, но
     * которое понадобилось во время производства (например, для допзамеса/поправки). Доступно
     * ADMIN и WAREHOUSE_RAW (см. @PreAuthorize в контроллерах) в ЛЮБОМ статусе заявки, кроме
     * отменённой — та же логика, что и у {@link #substituteItem}. Если это сырьё уже есть в
     * заявке — не создаёт вторую строку, а просто увеличивает количество существующей (иначе
     * пользователь получил бы задвоенную позицию в списке).
     *
     * <p>Если заявка уже собрана — сырьё уже физически списано со склада в {@link #collect}, а
     * тут добавляется НОВАЯ позиция, значит списание нужно сделать прямо сейчас (см.
     * {@link StockService#writeOffForProduction}); пока заявка ещё «Новая» — склад не трогаем,
     * спишется при сборке вместе со всеми остальными позициями.
     */
    @Transactional
    public ProductionRequest addItem(Long requestId, Long ingredientId, BigDecimal quantity, String by) {
        ProductionRequest request = findById(requestId);
        if (request.getStatus() == ProductionRequestStatus.CANCELLED) {
            throw new IllegalStateException("Нельзя менять состав отменённой заявки");
        }
        if (request.isRequisitionCompleted()) {
            throw new IllegalStateException("Заявка завершена — сначала отмените завершение");
        }
        if (quantity == null || quantity.signum() <= 0) {
            throw new IllegalArgumentException("Количество должно быть больше нуля");
        }
        Ingredient ingredient = ingredientRepository.findById(ingredientId)
                .orElseThrow(() -> new IllegalArgumentException("Сырьё не найдено: " + ingredientId));
        boolean stockAlreadyWrittenOff = request.getStatus() == ProductionRequestStatus.COLLECTED
                || request.getStatus() == ProductionRequestStatus.RECEIVED;
        String reason = "Заявка №" + request.getId() + " «" + request.getTitle() + "» — добавлена позиция";

        ProductionRequestItem existing = request.getItems().stream()
                .filter(i -> i.getIngredient().getId().equals(ingredientId))
                .findFirst().orElse(null);
        if (existing != null) {
            if (stockAlreadyWrittenOff) {
                stockService.writeOffForProduction(ingredient, quantity, reason, by);
            }
            existing.setRequiredQuantity(existing.getRequiredQuantity().add(quantity));
        } else {
            if (stockAlreadyWrittenOff) {
                stockService.writeOffForProduction(ingredient, quantity, reason, by);
            }
            ProductionRequestItem item = new ProductionRequestItem();
            item.setIngredient(ingredient);
            item.setRequiredQuantity(quantity);
            request.addItem(item);
        }

        oneCProductionSyncService.trySyncRequest(request);
        return request;
    }

    /**
     * Удаляет позицию сырья из заявки — например, добавили лишнюю через {@link #addItem} по
     * ошибке. Доступно ADMIN и WAREHOUSE_RAW (см. @PreAuthorize в контроллерах) в любом статусе,
     * кроме отменённой и завершённой. Если заявка уже собрана — сырьё уже физически списано со
     * склада в {@link #collect}, поэтому удаление позиции возвращает списанное количество
     * обратно (симметрично {@link #addItem}).
     */
    @Transactional
    public ProductionRequest removeItem(Long requestId, Long itemId, String by) {
        ProductionRequest request = findById(requestId);
        if (request.getStatus() == ProductionRequestStatus.CANCELLED) {
            throw new IllegalStateException("Нельзя менять состав отменённой заявки");
        }
        if (request.isRequisitionCompleted()) {
            throw new IllegalStateException("Заявка завершена — сначала отмените завершение");
        }
        ProductionRequestItem item = request.getItems().stream()
                .filter(i -> i.getId().equals(itemId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Позиция заявки не найдена: " + itemId));
        boolean stockAlreadyWrittenOff = request.getStatus() == ProductionRequestStatus.COLLECTED
                || request.getStatus() == ProductionRequestStatus.RECEIVED;
        if (stockAlreadyWrittenOff) {
            String reason = "Заявка №" + request.getId() + " «" + request.getTitle() + "» — удалена позиция";
            stockService.receiveForProduction(item.getIngredient(), item.getRequiredQuantity(), reason, by);
        }
        request.getItems().remove(item);

        oneCProductionSyncService.trySyncRequest(request);
        return request;
    }

    /**
     * "Завершить" — вручную проводит требование-накладную (списание сырья) в 1С, когда состав
     * заявки точно больше не изменится (см. OneCProductionSyncService.postRequisition —
     * бросает исключение с понятной причиной, если не удалось, тогда сводная нотификация ниже
     * не отправляется). Доступна только после ПОЛНОГО оприходования заявки (статус RECEIVED,
     * см. {@link #receiveLine}) — раньше была доступна уже после сборки (COLLECTED), но по
     * просьбе пользователя завершать требование-накладную нужно только когда весь факт выхода
     * продукции уже известен. Отчёт производства эта кнопка не трогает — он проводится сам при
     * полном оприходовании (см. {@link #receiveLine}). После завершения состав сырья менять
     * нельзя — редактировать/удалять/добавлять позиции скрыто в UI, пока не нажали "Отменить"
     * (см. {@link #cancelCompletion}).
     */
    @Transactional
    public ProductionRequest completeRequisition(Long requestId) {
        ProductionRequest request = findById(requestId);
        if (request.getStatus() != ProductionRequestStatus.RECEIVED) {
            throw new IllegalStateException("Завершить можно только полностью оприходованную заявку");
        }
        oneCProductionSyncService.postRequisition(request);
        request.setRequisitionCompleted(true);
        notifyReceivedSummary(request);
        return request;
    }

    /**
     * "Отменить" (после "Завершить") — распроводит требование-накладную в 1С и снова разрешает
     * редактировать/удалять/добавлять позиции сырья (см. {@link #completeRequisition}).
     */
    @Transactional
    public ProductionRequest cancelCompletion(Long requestId) {
        ProductionRequest request = findById(requestId);
        if (!request.isRequisitionCompleted()) {
            throw new IllegalStateException("Заявка ещё не завершена");
        }
        oneCProductionSyncService.unpostRequisition(request);
        request.setRequisitionCompleted(false);
        return request;
    }

    /**
     * Одна сводная push-нотификация "Оприходовано" по кнопке "Завершить" — вместо push на
     * каждую отдельно оприходованную строку (см. {@link #receiveLine}, убрано намеренно: в
     * заявке обычно несколько позиций продукции, оприходуются по одной, спамить на каждую не
     * нужно). Ничего не отправляет, если оприходованных строк ещё нет.
     */
    private void notifyReceivedSummary(ProductionRequest request) {
        List<ProductionRequestLine> received = request.getLines().stream()
                .filter(ProductionRequestLine::isReceived).toList();
        if (received.isEmpty()) {
            return;
        }
        String summary = received.stream()
                .map(l -> l.getReceivedQuantity().stripTrailingZeros().toPlainString() + " " + safeUnit(l.getProduct())
                        + " «" + l.getProduct().getName() + "»")
                .collect(Collectors.joining(", "));
        String message = "Заявка №" + request.getId() + " «" + request.getTitle() + "» — оприходовано: " + summary;
        notificationService.notifyRole(Role.ADMIN, message, null);
        notificationService.notifyRole(Role.ACCOUNTANT, message, null);
    }

    /** Только WAREHOUSE_RAW — именно он собирает заявку по чек-листу, остальным push не нужен. */
    private void notifyCreated(ProductionRequest request) {
        BigDecimal totalBatches = request.getLines().stream()
                .map(ProductionRequestLine::getBatches).reduce(BigDecimal.ZERO, BigDecimal::add);
        String lineSummary = request.getLines().stream()
                .map(l -> l.getProduct().getName() + " — " + l.getBatches().stripTrailingZeros().toPlainString() + " замес.")
                .collect(Collectors.joining(", "));
        String message = "Новая заявка №" + request.getId() + " «" + request.getTitle() + "»: "
                + totalBatches.stripTrailingZeros().toPlainString() + " замес. всего (" + lineSummary + ")";
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
        return request;
    }

    /**
     * Зав. склада ГП вводит фактически полученное количество по ОДНОЙ строке заявки — это и
     * есть оприходование. Когда оприходованы все строки, вся заявка помечается RECEIVED. Каждое
     * оприходование сразу корректирует уже выгруженные в 1С документы (см.
     * OneCProductionSyncService) — количество этой строки в отчёте производства заменяется с
     * планового на фактическое. Отчёт производства CRM больше не проводит сама (как и
     * "Реализацию" заказов — Document.Posted=true через OData оказался ненадёжен: 1С отвечала
     * 200 OK, но реальных движений по складу не формировала, пока документ не открывали в самой
     * 1С и не нажимали "Провести" вручную) — бухгалтер проводит его в 1С сам. Требование-накладную
     * тоже не трогает — её проводит отдельно кнопка "Завершить" (см. {@link #completeRequisition}).
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

        // Без push на каждую отдельно оприходованную строку — в заявке обычно несколько
        // позиций, оприходуются по одной, спамить на каждую не нужно. Одна сводная
        // нотификация уходит по кнопке "Завершить" (см. completeRequisition/notifyReceivedSummary).
        oneCProductionSyncService.trySyncRequest(request);
        return request;
    }

    /**
     * Ручная повторная попытка синхронизировать заявку с 1С — на случай, если автоматическая
     * (при создании/редактировании/замене сырья/оприходовании — см. OneCProductionSyncService)
     * не сработала (например, backend был поднят без переменных окружения 1С) или закончилась
     * ошибкой. Работает в любом статусе заявки: сама решает, создавать документы в 1С или
     * скорректировать уже существующие.
     */
    @Transactional
    public ProductionRequest resyncOneC(Long requestId) {
        ProductionRequest request = findById(requestId);
        oneCProductionSyncService.syncOrThrow(request);
        return request;
    }

    /** Дневной отчёт (для мобильного API — см. ProductionRequestApiController) — один день, обёртка над {@link #report}. */
    public List<ReportLine> dailyReport(LocalDate date) {
        return report(date, date);
    }

    /**
     * Сколько готовой продукции реально оприходовано за период [from, to] (включительно) — по
     * всем заявкам, сгруппировано по товару (в заявке "Коржик" может быть 3 строки на 3 разных
     * товара — здесь они не смешиваются, каждый товар своей строкой). Оприходование конкретной
     * строки может случиться позже создания заявки (например, заявка собрана вечером,
     * оприходована утром) — отчёт считает именно по дате ОПРИХОДОВАНИЯ (receivedAt), не по дате
     * заявки. {@code from}/{@code to} оба null — отчёт "за всё время" (веб-страница /
     * PDF, см. ProductionRequestController).
     */
    public List<ReportLine> report(LocalDate from, LocalDate to) {
        List<ProductionRequestLine> lines = (from == null || to == null)
                ? lineRepository.findByReceivedAtIsNotNullOrderByReceivedAtAsc()
                : lineRepository.findByReceivedAtBetweenOrderByReceivedAtAsc(
                        from.atStartOfDay(), to.plusDays(1).atStartOfDay());

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
