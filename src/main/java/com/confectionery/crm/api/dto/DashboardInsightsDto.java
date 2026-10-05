package com.confectionery.crm.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Дашборд «График + Требует внимания»: продажи по дням за 7 дней и предупреждения. */
public record DashboardInsightsDto(
        List<DaySales> days,
        BigDecimal weekTotal,
        BigDecimal prevWeekTotal,
        List<Alert> alerts
) {
    public record DaySales(LocalDate date, BigDecimal amount) {
    }

    /** kind: LOW_STOCK | OVERDUE_DEBT | STUCK_ORDER; orderId — куда вести по нажатию, если есть. */
    public record Alert(String kind, String title, String meta, Long orderId) {
    }
}
