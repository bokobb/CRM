package com.confectionery.crm.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Себестоимость производства за день (экран «Производство»): заявки на сырьё с этой датой
 * документа. Норма — рецептура техкарты × замесы, факт — сырьё, реально выданное по заявке
 * (состав после правок), разнесённое по товарам заявки пропорционально норме. Зарплата —
 * ставки бригады заявки за смену, разносится так же.
 * Цены — {@link com.confectionery.crm.service.IngredientPriceService} на дату дня.
 * Поля labor* = null, если роли не положено видеть зарплату (технолог) — тогда и
 * себестоимость единицы без зарплаты.
 */
public record ProductionCostDto(
        LocalDate date,
        Totals totals,
        List<Request> requests,
        List<Day> history,
        List<String> unpricedIngredients
) {
    /**
     * finished* — только готовая продукция (полуфабрикаты в выручку не идут, их себестоимость
     * войдёт в готовый товар в день упаковки — отдельно semiFinishedCost, чтобы не считать дважды).
     * finishedCost включает зарплату, если она видна.
     */
    public record Totals(BigDecimal normCost, BigDecimal issuedCost, BigDecimal laborCost, BigDecimal finishedCost,
                         BigDecimal finishedSaleValue, BigDecimal semiFinishedCost) {
    }

    public record Request(Long id, String title, String status, String statusLabel,
                          BigDecimal normCost, BigDecimal issuedCost, BigDecimal laborCost,
                          List<Member> brigade, List<Line> lines) {
    }

    public record Member(Long employeeId, String fullName, BigDecimal dailyRate) {
    }

    /**
     * quantity — оприходовано, а пока не оприходовано — план (received=false).
     * cost — сырьё (факт) + зарплата; unitCost — на quantity; planUnitCost — норма сырья + зарплата на план.
     */
    public record Line(Long productId, String name, String unit, boolean semiFinished, BigDecimal batches,
                       BigDecimal plannedQuantity, BigDecimal quantity, boolean received,
                       BigDecimal normCost, BigDecimal rawCost, BigDecimal laborCost, BigDecimal cost,
                       BigDecimal unitCost, BigDecimal planUnitCost, BigDecimal salePrice) {
    }

    public record Day(LocalDate date, BigDecimal finishedCost, BigDecimal finishedSaleValue,
                      BigDecimal normCost, BigDecimal issuedCost) {
    }

    /** Экран заявки: шапка + строки (как в списке) + сырьё норма/выдано с ценами. */
    public record RequestDetail(LocalDate date, Request request, List<Ingredient> ingredients,
                                List<String> unpricedIngredients) {
    }

    /** priceSource: PURCHASE — последняя закупка (priceDate), SEMI_FINISHED — по рецептуре п/ф, null — нет цены. */
    public record Ingredient(Long ingredientId, String name, String unit, BigDecimal normQuantity,
                             BigDecimal issuedQuantity, BigDecimal unitPrice, LocalDate priceDate, String priceSource,
                             BigDecimal normCost, BigDecimal issuedCost, String originalName) {
    }
}
