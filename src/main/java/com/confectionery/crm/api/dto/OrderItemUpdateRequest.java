package com.confectionery.crm.api.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

public record OrderItemUpdateRequest(
        @NotNull List<ItemUpdate> items
) {
    /** productId — null, если товар в позиции не меняется (меняется только количество). */
    public record ItemUpdate(@NotNull Long itemId, @NotNull BigDecimal quantity, Long productId) {
    }
}
