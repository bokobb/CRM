package com.confectionery.crm.api.dto;

import com.confectionery.crm.domain.ProductionRequest;
import com.confectionery.crm.domain.ProductionRequestItem;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record ProductionRequestDto(
        Long id,
        Long productId,
        String productName,
        String productUnit,
        BigDecimal batches,
        String status,
        String statusLabel,
        String createdBy,
        LocalDateTime createdAt,
        String collectedBy,
        LocalDateTime collectedAt,
        String receivedBy,
        LocalDateTime receivedAt,
        BigDecimal receivedQuantity,
        List<Item> items
) {
    public record Item(Long id, Long ingredientId, String ingredientName, String ingredientUnit, BigDecimal requiredQuantity) {
        static Item from(ProductionRequestItem i) {
            return new Item(i.getId(), i.getIngredient().getId(), i.getIngredient().getName(),
                    i.getIngredient().getUnit(), i.getRequiredQuantity());
        }
    }

    public static ProductionRequestDto from(ProductionRequest r) {
        return new ProductionRequestDto(
                r.getId(),
                r.getProduct().getId(),
                r.getProduct().getName(),
                r.getProduct().getUnit(),
                r.getBatches(),
                r.getStatus().name(),
                r.getStatus().getLabel(),
                r.getCreatedBy(),
                r.getCreatedAt(),
                r.getCollectedBy(),
                r.getCollectedAt(),
                r.getReceivedBy(),
                r.getReceivedAt(),
                r.getReceivedQuantity(),
                r.getItems().stream().map(Item::from).toList()
        );
    }
}
