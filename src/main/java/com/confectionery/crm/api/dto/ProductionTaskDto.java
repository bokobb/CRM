package com.confectionery.crm.api.dto;

import com.confectionery.crm.domain.ProductionTask;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ProductionTaskDto(
        Long id,
        Long orderId,
        Long productId,
        String productName,
        BigDecimal quantity,
        String status,
        String statusLabel,
        String assignedTo,
        LocalDateTime createdAt,
        LocalDateTime completedAt
) {
    public static ProductionTaskDto from(ProductionTask t) {
        return new ProductionTaskDto(
                t.getId(), t.getOrder().getId(), t.getProduct().getId(), t.getProduct().getName(),
                t.getQuantity(), t.getStatus().name(), t.getStatus().getLabel(),
                t.getAssignedTo(), t.getCreatedAt(), t.getCompletedAt()
        );
    }
}
