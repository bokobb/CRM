package com.confectionery.crm.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Дашборд склада ГП (WAREHOUSE_FG): сборка и отгрузка заказов, приём продукции из цеха, остатки ГП. */
public record WarehouseDashboardDto(
        LocalDate from,
        LocalDate to,
        long toAssemble,
        long readyToShip,
        long shippedInRange,
        long awaitingReceipt,
        List<StockLine> lowStock,
        List<OrderSummaryDto> activeOrders
) {
    public record StockLine(Long id, String name, String unit, BigDecimal quantityInStock) {
    }
}
