package com.confectionery.crm.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record ProductReturnRequest(
        @NotNull Long clientId,
        @NotEmpty @Valid List<Item> items,
        LocalDate returnDate,
        String reason
) {
    /** price — необязательно, по умолчанию цена клиента. */
    public record Item(@NotNull Long productId, @NotNull BigDecimal quantity, BigDecimal price) {
    }
}
