package com.confectionery.crm.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record PurchaseRequest(
        @NotNull Long counterpartyId,
        @NotEmpty @Valid List<Item> items,
        LocalDate purchaseDate
) {
    /** totalPrice — необязательно, не всегда известна цена по каждой позиции отдельно. */
    public record Item(@NotNull Long ingredientId, @NotNull BigDecimal quantity, BigDecimal totalPrice) {
    }
}
