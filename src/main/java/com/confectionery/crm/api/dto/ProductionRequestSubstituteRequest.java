package com.confectionery.crm.api.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/** quantity — необязательно, null оставляет прежнее количество (см. ProductionRequestService.substituteItem). */
public record ProductionRequestSubstituteRequest(@NotNull Long ingredientId, BigDecimal quantity) {
}
