package com.confectionery.crm.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record ProductionRequestCreateRequest(@NotEmpty @Valid List<ProductionRequestLineRequest> items) {
}
