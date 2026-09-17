package com.confectionery.crm.api.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/** См. ProductionRequestService.addItem — добавление НОВОЙ позиции сырья в уже существующую заявку. */
public record ProductionRequestAddItemRequest(@NotNull Long ingredientId, @NotNull BigDecimal quantity) {
}
