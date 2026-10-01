package com.confectionery.crm.service;

import com.confectionery.crm.api.dto.ProductionCostDto;
import com.confectionery.crm.domain.Employee;
import com.confectionery.crm.domain.Ingredient;
import com.confectionery.crm.domain.Product;
import com.confectionery.crm.domain.ProductionBrigadeMember;
import com.confectionery.crm.domain.ProductionRequest;
import com.confectionery.crm.domain.ProductionRequestItem;
import com.confectionery.crm.domain.ProductionRequestLine;
import com.confectionery.crm.domain.ProductionRequestStatus;
import com.confectionery.crm.domain.Recipe;
import com.confectionery.crm.domain.RecipeItem;
import com.confectionery.crm.repository.ProductionRequestRepository;
import com.confectionery.crm.repository.RecipeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Себестоимость производства по дням — заявки на сырьё × техкарты + бригады (см. {@link ProductionCostDto}). */
@Service
@RequiredArgsConstructor
public class ProductionCostService {

    /** Сколько дней истории до выбранной даты включительно — график на экране. */
    private static final int HISTORY_DAYS = 14;

    private final ProductionRequestRepository requestRepository;
    private final RecipeRepository recipeRepository;
    private final IngredientPriceService ingredientPriceService;

    /** @param showLabor false — роль не видит зарплату: labor* = null, себестоимость только по сырью. */
    @Transactional(readOnly = true)
    public ProductionCostDto day(LocalDate date, boolean showLabor) {
        Map<Long, Recipe> recipes = recipes();
        Map<LocalDate, List<ProductionRequest>> byDate = requestRepository
                .findByDocumentDateBetweenAndStatusNot(date.minusDays(HISTORY_DAYS - 1), date, ProductionRequestStatus.CANCELLED)
                .stream().collect(Collectors.groupingBy(ProductionRequest::getDocumentDate));

        List<ProductionCostDto.Day> history = new ArrayList<>();
        ProductionCostDto selected = null;
        for (LocalDate d = date.minusDays(HISTORY_DAYS - 1); !d.isAfter(date); d = d.plusDays(1)) {
            ProductionCostDto r = computeDay(d, byDate.getOrDefault(d, List.of()), recipes, showLabor);
            history.add(new ProductionCostDto.Day(d, r.totals().finishedCost(), r.totals().finishedSaleValue(),
                    r.totals().normCost(), r.totals().issuedCost()));
            if (d.equals(date)) selected = r;
        }
        return new ProductionCostDto(date, selected.totals(), selected.requests(), history, selected.unpricedIngredients());
    }

    @Transactional(readOnly = true)
    public ProductionCostDto.RequestDetail request(Long requestId, boolean showLabor) {
        ProductionRequest req = requestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Заявка не найдена"));
        Map<Long, Recipe> recipes = recipes();
        IngredientPriceService.Calculator calc = ingredientPriceService.calculator(req.getDocumentDate());
        Set<String> unpriced = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        RequestCalc rc = computeRequest(req, recipes, calc, unpriced, showLabor);

        // Сырьё: норма (Σ рецептура × замесы по всем строкам) и выдано (позиции заявки) — по виду сырья.
        Map<Long, Ingredient> byId = new LinkedHashMap<>();
        Map<Long, BigDecimal> normQty = new LinkedHashMap<>();
        Map<Long, BigDecimal> issuedQty = new LinkedHashMap<>();
        Map<Long, String> originalNames = new HashMap<>();
        for (ProductionRequestLine l : req.getLines()) {
            Recipe recipe = recipes.get(l.getProduct().getId());
            if (recipe == null) continue;
            for (RecipeItem ri : recipe.getItems()) {
                byId.putIfAbsent(ri.getIngredient().getId(), ri.getIngredient());
                normQty.merge(ri.getIngredient().getId(), nz(ri.getQuantity()).multiply(l.getBatches()), BigDecimal::add);
            }
        }
        for (ProductionRequestItem it : req.getItems()) {
            byId.putIfAbsent(it.getIngredient().getId(), it.getIngredient());
            issuedQty.merge(it.getIngredient().getId(), nz(it.getRequiredQuantity()), BigDecimal::add);
            if (it.getOriginalIngredientName() != null) originalNames.put(it.getIngredient().getId(), it.getOriginalIngredientName());
        }
        List<ProductionCostDto.Ingredient> ingredients = new ArrayList<>();
        for (Ingredient ing : byId.values()) {
            Optional<IngredientPriceService.Price> price = calc.price(ing);
            BigDecimal unitPrice = price.map(IngredientPriceService.Price::unitPrice).orElse(null);
            BigDecimal n = normQty.getOrDefault(ing.getId(), BigDecimal.ZERO);
            BigDecimal is = issuedQty.getOrDefault(ing.getId(), BigDecimal.ZERO);
            ingredients.add(new ProductionCostDto.Ingredient(ing.getId(), ing.getName(), ing.getUnit(),
                    n.stripTrailingZeros(), is.stripTrailingZeros(), unitPrice,
                    price.map(IngredientPriceService.Price::date).orElse(null),
                    price.map(p -> p.source().name()).orElse(null),
                    unitPrice == null ? null : money(unitPrice.multiply(n)),
                    unitPrice == null ? null : money(unitPrice.multiply(is)),
                    originalNames.get(ing.getId())));
        }
        // Дороже — выше: так видно, что весит в себестоимости.
        ingredients.sort(Comparator.comparing((ProductionCostDto.Ingredient i) ->
                i.issuedCost() == null ? BigDecimal.ZERO : i.issuedCost().max(i.normCost())).reversed());
        return new ProductionCostDto.RequestDetail(req.getDocumentDate(), rc.dto, ingredients, List.copyOf(unpriced));
    }

    private Map<Long, Recipe> recipes() {
        return recipeRepository.findAll().stream().collect(Collectors.toMap(r -> r.getProduct().getId(), Function.identity()));
    }

    private record RequestCalc(ProductionCostDto.Request dto, BigDecimal norm, BigDecimal issued, BigDecimal labor,
                               BigDecimal fgCost, BigDecimal fgSale, BigDecimal semiCost) {
    }

    private ProductionCostDto computeDay(LocalDate date, List<ProductionRequest> requests, Map<Long, Recipe> recipes, boolean showLabor) {
        IngredientPriceService.Calculator calc = ingredientPriceService.calculator(date);
        Set<String> unpriced = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        // Все цены дня одним запросом.
        calc.prices(Stream.concat(
                requests.stream().flatMap(r -> r.getItems().stream()).map(ProductionRequestItem::getIngredient),
                requests.stream().flatMap(r -> r.getLines().stream())
                        .map(l -> recipes.get(l.getProduct().getId())).filter(Objects::nonNull)
                        .flatMap(rc -> rc.getItems().stream()).map(RecipeItem::getIngredient)
        ).toList());

        BigDecimal norm = BigDecimal.ZERO, issued = BigDecimal.ZERO, labor = BigDecimal.ZERO, fgCost = BigDecimal.ZERO,
                fgSale = BigDecimal.ZERO, semiCost = BigDecimal.ZERO;
        List<ProductionCostDto.Request> out = new ArrayList<>();
        for (ProductionRequest req : requests.stream().sorted(Comparator.comparing(ProductionRequest::getId)).toList()) {
            RequestCalc rc = computeRequest(req, recipes, calc, unpriced, showLabor);
            norm = norm.add(rc.norm);
            issued = issued.add(rc.issued);
            labor = labor.add(rc.labor);
            fgCost = fgCost.add(rc.fgCost);
            fgSale = fgSale.add(rc.fgSale);
            semiCost = semiCost.add(rc.semiCost);
            out.add(rc.dto);
        }
        return new ProductionCostDto(date, new ProductionCostDto.Totals(money(norm), money(issued),
                showLabor ? money(labor) : null, money(fgCost), money(fgSale), money(semiCost)),
                out, List.of(), List.copyOf(unpriced));
    }

    private RequestCalc computeRequest(ProductionRequest req, Map<Long, Recipe> recipes, IngredientPriceService.Calculator calc,
                                       Set<String> unpriced, boolean showLabor) {
        List<ProductionRequestLine> lines = req.getLines();
        List<BigDecimal> lineNorms = lines.stream().map(l -> {
            Recipe rc = recipes.get(l.getProduct().getId());
            if (rc == null) return BigDecimal.ZERO;
            BigDecimal perBatch = BigDecimal.ZERO;
            for (RecipeItem ri : rc.getItems()) {
                perBatch = perBatch.add(cost(calc, ri.getIngredient(), ri.getQuantity(), unpriced));
            }
            return perBatch.multiply(l.getBatches());
        }).toList();
        BigDecimal reqNorm = sum(lineNorms);
        BigDecimal reqIssued = BigDecimal.ZERO;
        for (ProductionRequestItem it : req.getItems()) {
            reqIssued = reqIssued.add(cost(calc, it.getIngredient(), it.getRequiredQuantity(), unpriced));
        }
        List<ProductionBrigadeMember> brigade = req.getBrigade().stream()
                .sorted(Comparator.comparing(m -> m.getEmployee().getFullName(), String.CASE_INSENSITIVE_ORDER)).toList();
        BigDecimal reqLabor = showLabor
                ? sum(brigade.stream().map(m -> nz(m.getEmployee().getDailyRate())).toList())
                : BigDecimal.ZERO;
        BigDecimal plannedTotal = sum(lines.stream().map(l -> nz(l.getPlannedQuantity())).toList());

        BigDecimal fgCost = BigDecimal.ZERO, fgSale = BigDecimal.ZERO, semiCost = BigDecimal.ZERO;
        List<ProductionCostDto.Line> outLines = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            ProductionRequestLine l = lines.get(i);
            Product p = l.getProduct();
            // Факт и зарплата разносятся по товарам заявки пропорционально норме (без нормы — по плану в штуках).
            BigDecimal share = reqNorm.signum() > 0 ? lineNorms.get(i).divide(reqNorm, 10, RoundingMode.HALF_UP)
                    : plannedTotal.signum() > 0 ? nz(l.getPlannedQuantity()).divide(plannedTotal, 10, RoundingMode.HALF_UP)
                    : BigDecimal.ONE.divide(BigDecimal.valueOf(lines.size()), 10, RoundingMode.HALF_UP);
            BigDecimal raw = reqIssued.multiply(share);
            BigDecimal lineLabor = reqLabor.multiply(share);
            BigDecimal lineCost = raw.add(lineLabor);
            boolean received = l.isReceived();
            BigDecimal planned = nz(l.getPlannedQuantity());
            BigDecimal qty = received ? l.getReceivedQuantity() : planned;
            BigDecimal unitCost = qty.signum() > 0 ? lineCost.divide(qty, 2, RoundingMode.HALF_UP) : null;
            BigDecimal planUnitCost = planned.signum() > 0
                    ? lineNorms.get(i).add(lineLabor).divide(planned, 2, RoundingMode.HALF_UP) : null;
            BigDecimal salePrice = p.isSemiFinished() ? null : p.getPrice();
            if (p.isSemiFinished()) {
                semiCost = semiCost.add(lineCost);
            } else {
                fgCost = fgCost.add(lineCost);
                if (salePrice != null) fgSale = fgSale.add(salePrice.multiply(qty));
            }
            outLines.add(new ProductionCostDto.Line(p.getId(), p.getName(), p.getUnit(), p.isSemiFinished(),
                    l.getBatches(), planned, qty, received, money(lineNorms.get(i)), money(raw),
                    showLabor ? money(lineLabor) : null, money(lineCost), unitCost, planUnitCost, salePrice));
        }
        List<ProductionCostDto.Member> members = brigade.stream().map(m -> {
            Employee e = m.getEmployee();
            return new ProductionCostDto.Member(e.getId(), e.getFullName(), showLabor ? e.getDailyRate() : null);
        }).toList();
        ProductionCostDto.Request dto = new ProductionCostDto.Request(req.getId(), req.getTitle(), req.getStatus().name(),
                req.getStatus().getLabel(), money(reqNorm), money(reqIssued), showLabor ? money(reqLabor) : null,
                members, outLines);
        return new RequestCalc(dto, reqNorm, reqIssued, reqLabor, fgCost, fgSale, semiCost);
    }

    private static BigDecimal cost(IngredientPriceService.Calculator calc, Ingredient ingredient, BigDecimal qty, Set<String> unpriced) {
        Optional<IngredientPriceService.Price> price = calc.price(ingredient);
        if (price.isEmpty()) {
            unpriced.add(ingredient.getName());
            return BigDecimal.ZERO;
        }
        return price.get().unitPrice().multiply(nz(qty));
    }

    private static BigDecimal sum(List<BigDecimal> values) {
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
