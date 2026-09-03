package com.confectionery.crm.api.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record ProductionRequestReceiveRequest(@NotNull BigDecimal quantity) {
}
