package com.confectionery.crm.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Дашборд «Сырьё · график»: собранные замесы по дням за 7 дней и предупреждения по сырью. */
public record RawInsightsDto(
        List<Day> days,
        BigDecimal weekBatches,
        BigDecimal prevWeekBatches,
        long pending,
        long awaitingReceipt,
        List<Alert> alerts
) {
    public record Day(LocalDate date, BigDecimal batches, long requests) {
    }

    /** kind: LOW_STOCK | STUCK_REQUEST | AWAITING_RECEIPT; requestId — куда вести по нажатию. */
    public record Alert(String kind, String title, String meta, Long requestId) {
    }
}
