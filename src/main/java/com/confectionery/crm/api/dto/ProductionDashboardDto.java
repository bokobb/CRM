package com.confectionery.crm.api.dto;

import java.time.LocalDate;
import java.util.List;

/** Дашборд для роли WAREHOUSE_RAW — про производство (заявки на сырьё), не про продажи. */
public record ProductionDashboardDto(
        LocalDate from,
        LocalDate to,
        long pending,
        long awaitingReceipt,
        long createdInRange,
        long collectedInRange,
        List<IngredientDto> lowStock,
        List<ProductionRequestSummaryDto> pendingRequests
) {
}
