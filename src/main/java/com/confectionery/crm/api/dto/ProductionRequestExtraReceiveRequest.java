package com.confectionery.crm.api.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record ProductionRequestExtraReceiveRequest(@NotNull Long productId, @NotNull BigDecimal quantity) {
}
