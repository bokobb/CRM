package com.confectionery.crm.api.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/** itemIds должны покрывать ВСЕ позиции заявки — иначе сборка отклоняется (см. ProductionRequestService.collect). */
public record ProductionRequestCollectRequest(@NotNull List<Long> itemIds) {
}
