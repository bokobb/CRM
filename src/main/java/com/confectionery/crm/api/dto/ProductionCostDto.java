package com.confectionery.crm.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Себестоимость производства за день (экран «Производство»): заявки на сырьё с этой датой
 * документа. Норма — рецептура техкарты × замесы, факт — сырьё, реально выданное по заявке
 * (состав после правок), разнесённое по товарам заявки пропорционально норме.
 * Цены — {@link com.confectionery.crm.service.IngredientPriceService} на дату дня.
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
     */
    public record Totals(BigDecimal normCost, BigDecimal issuedCost, BigDecimal finishedCost,
                         BigDecimal finishedSaleValue, BigDecimal semiFinishedCost) {
    }

    public record Request(Long id, String title, String status, String statusLabel,
                          BigDecimal normCost, BigDecimal issuedCost, List<Line> lines) {
    }

    /** quantity — оприходовано, а пока не оприходовано — план (received=false). */
    public record Line(Long productId, String name, String unit, boolean semiFinished, BigDecimal batches,
                       BigDecimal quantity, boolean received, BigDecimal normCost, BigDecimal cost,
                       BigDecimal unitCost, BigDecimal salePrice) {
    }

    public record Day(LocalDate date, BigDecimal finishedCost, BigDecimal finishedSaleValue,
                      BigDecimal normCost, BigDecimal issuedCost) {
    }
}
