package com.confectionery.crm.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Облегчённая версия заказа для списков — без позиций и задач цеха. */
public record OrderSummaryDto(
        Long id,
        Long clientId,
        String clientName,
        String status,
        String statusLabel,
        LocalDateTime createdAt,
        LocalDate dueDate,
        BigDecimal totalPrice
) {
}
