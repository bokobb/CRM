package com.confectionery.crm.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.time.LocalDate;
import java.util.List;

/** documentDate — дата документа для 1С (см. ProductionRequest.documentDate); null — сегодня (при создании) или не менять (при редактировании). */
public record ProductionRequestCreateRequest(@NotEmpty @Valid List<ProductionRequestLineRequest> items, LocalDate documentDate) {
}
