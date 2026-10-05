package com.confectionery.crm.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Лента дня: всё, что произошло за дату, от новых к старым. */
public record DayFeedDto(LocalDate date, BigDecimal salesTotal, long ordersCreated, List<Event> events) {

    /** kind: ORDER_NEW | ORDER_SHIPPED | RETURN | REQUEST_NEW | REQUEST_COLLECTED. */
    public record Event(LocalDateTime time, String kind, String title, String meta, Long orderId, Long requestId) {
    }
}
