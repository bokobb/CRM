package com.confectionery.crm.api.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

public record OrderItemUpdateRequest(
        @NotNull List<ItemUpdate> items
) {
    public record ItemUpdate(@NotNull Long itemId, @NotNull BigDecimal quantity) {
    }
}
