package com.confectionery.crm.api.dto;

import com.confectionery.crm.domain.ProductionRequest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Для списка заявок — без сырьевых позиций, чтобы не гонять лишние данные по сети. */
public record ProductionRequestSummaryDto(
        Long id,
        String title,
        List<String> productNames,
        BigDecimal totalBatches,
        String status,
        String statusLabel,
        String createdBy,
        LocalDateTime createdAt
) {
    public static ProductionRequestSummaryDto from(ProductionRequest r) {
        BigDecimal total = r.getLines().stream().map(l -> l.getBatches()).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new ProductionRequestSummaryDto(
                r.getId(), r.getTitle(), r.getLines().stream().map(l -> l.getProduct().getName()).toList(), total,
                r.getStatus().name(), r.getStatus().getLabel(), r.getCreatedBy(), r.getCreatedAt()
        );
    }
}
