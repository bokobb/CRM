package com.confectionery.crm.service;

import com.confectionery.crm.api.dto.ProductionCostDto;
import com.confectionery.crm.domain.Ingredient;
import com.confectionery.crm.domain.Product;
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

/** Себестоимость производства по дням — заявки на сырьё × техкарты (см. {@link ProductionCostDto}). */
@Service
@RequiredArgsConstructor
public class ProductionCostService {

    /** Сколько дней истории до выбранной даты включительно — график на экране. */
    private static final int HISTORY_DAYS = 14;

    private final ProductionRequestRepository requestRepository;
    private final RecipeRepository recipeRepository;
    private final IngredientPriceService ingredientPriceService;

    @Transactional(readOnly = true)
    public ProductionCostDto day(LocalDate date) {
        Map<Long, Recipe> recipes = recipeRepository.findAll().stream()
                .collect(Collectors.toMap(r -> r.getProduct().getId(), Function.identity()));
        Map<LocalDate, List<ProductionRequest>> byDate = requestRepository
                .findByDocumentDateBetweenAndStatusNot(date.minusDays(HISTORY_DAYS - 1), date, ProductionRequestStatus.CANCELLED)
                .stream().collect(Collectors.groupingBy(ProductionRequest::getDocumentDate));

        List<ProductionCostDto.Day> history = new ArrayList<>();
        DayResult selected = null;
        for (LocalDate d = date.minusDays(HISTORY_DAYS - 1); !d.isAfter(date); d = d.plusDays(1)) {
            DayResult r = compute(d, byDate.getOrDefault(d, List.of()), recipes);
            history.add(new ProductionCostDto.Day(d, r.totals.finishedCost(), r.totals.finishedSaleValue(),
                    r.totals.normCost(), r.totals.issuedCost()));
            if (d.equals(date)) selected = r;
        }
        return new ProductionCostDto(date, selected.totals, selected.requests, history,
                selected.unpriced.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList());
    }

    private record DayResult(ProductionCostDto.Totals totals, List<ProductionCostDto.Request> requests, Set<String> unpriced) {
    }

    private DayResult compute(LocalDate date, List<ProductionRequest> requests, Map<Long, Recipe> recipes) {
        IngredientPriceService.Calculator calc = ingredientPriceService.calculator(date);
        Set<String> unpriced = new TreeSet<>();
        // Все цены дня одним запросом.
        calc.prices(Stream.concat(
                requests.stream().flatMap(r -> r.getItems().stream()).map(ProductionRequestItem::getIngredient),
                requests.stream().flatMap(r -> r.getLines().stream())
                        .map(l -> recipes.get(l.getProduct().getId())).filter(Objects::nonNull)
                        .flatMap(rc -> rc.getItems().stream()).map(RecipeItem::getIngredient)
        ).toList());

        BigDecimal norm = BigDecimal.ZERO, issued = BigDecimal.ZERO, fgCost = BigDecimal.ZERO,
                fgSale = BigDecimal.ZERO, semiCost = BigDecimal.ZERO;
        List<ProductionCostDto.Request> out = new ArrayList<>();
        for (ProductionRequest req : requests.stream().sorted(Comparator.comparing(ProductionRequest::getId)).toList()) {
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
            BigDecimal plannedTotal = sum(lines.stream().map(l -> nz(l.getPlannedQuantity())).toList());

            List<ProductionCostDto.Line> outLines = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                ProductionRequestLine l = lines.get(i);
                Product p = l.getProduct();
                // Факт разносится по товарам заявки пропорционально норме (без нормы — по плану в штуках).
                BigDecimal share = reqNorm.signum() > 0 ? lineNorms.get(i).divide(reqNorm, 10, RoundingMode.HALF_UP)
                        : plannedTotal.signum() > 0 ? nz(l.getPlannedQuantity()).divide(plannedTotal, 10, RoundingMode.HALF_UP)
                        : BigDecimal.ONE.divide(BigDecimal.valueOf(lines.size()), 10, RoundingMode.HALF_UP);
                BigDecimal lineCost = reqIssued.multiply(share);
                boolean received = l.isReceived();
                BigDecimal qty = received ? l.getReceivedQuantity() : nz(l.getPlannedQuantity());
                BigDecimal unitCost = qty.signum() > 0 ? lineCost.divide(qty, 2, RoundingMode.HALF_UP) : null;
                BigDecimal salePrice = p.isSemiFinished() ? null : p.getPrice();
                if (p.isSemiFinished()) {
                    semiCost = semiCost.add(lineCost);
                } else {
                    fgCost = fgCost.add(lineCost);
                    if (salePrice != null) fgSale = fgSale.add(salePrice.multiply(qty));
                }
                outLines.add(new ProductionCostDto.Line(p.getId(), p.getName(), p.getUnit(), p.isSemiFinished(),
                        l.getBatches(), qty, received, money(lineNorms.get(i)), money(lineCost), unitCost, salePrice));
            }
            norm = norm.add(reqNorm);
            issued = issued.add(reqIssued);
            out.add(new ProductionCostDto.Request(req.getId(), req.getTitle(), req.getStatus().name(),
                    req.getStatus().getLabel(), money(reqNorm), money(reqIssued), outLines));
        }
        return new DayResult(new ProductionCostDto.Totals(money(norm), money(issued), money(fgCost), money(fgSale), money(semiCost)),
                out, unpriced);
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
