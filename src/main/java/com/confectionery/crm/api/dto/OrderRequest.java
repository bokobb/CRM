package com.confectionery.crm.api.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record OrderRequest(
        @NotNull Long clientId,
        LocalDate dueDate,
        String notes,
        List<OrderItemRequest> items
) {
    public record OrderItemRequest(Long productId, BigDecimal quantity) {
    }
}
