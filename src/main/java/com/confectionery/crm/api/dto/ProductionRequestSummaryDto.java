package com.confectionery.crm.api.dto;

import com.confectionery.crm.domain.ProductionRequest;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Для списка заявок — без позиций рецепта, чтобы не гонять лишние данные по сети. */
public record ProductionRequestSummaryDto(
        Long id,
        String productName,
        BigDecimal batches,
        String status,
        String statusLabel,
        String createdBy,
        LocalDateTime createdAt
) {
    public static ProductionRequestSummaryDto from(ProductionRequest r) {
        return new ProductionRequestSummaryDto(
                r.getId(), r.getProduct().getName(), r.getBatches(),
                r.getStatus().name(), r.getStatus().getLabel(), r.getCreatedBy(), r.getCreatedAt()
        );
    }
}
