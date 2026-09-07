package com.confectionery.crm.api.dto;

import com.confectionery.crm.domain.ProductionRequest;
import com.confectionery.crm.domain.ProductionRequestItem;
import com.confectionery.crm.domain.ProductionRequestLine;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record ProductionRequestDto(
        Long id,
        String title,
        String status,
        String statusLabel,
        String createdBy,
        LocalDateTime createdAt,
        LocalDate documentDate,
        String collectedBy,
        LocalDateTime collectedAt,
        String oneCReportNumber,
        String oneCRequisitionNumber,
        String oneCSyncError,
        List<Line> lines,
        List<Item> items
) {
    public record Line(
            Long id, Long productId, String productName, String productUnit,
            BigDecimal batches, BigDecimal plannedQuantity,
            String receivedBy, LocalDateTime receivedAt, BigDecimal receivedQuantity
    ) {
        static Line from(ProductionRequestLine l) {
            return new Line(l.getId(), l.getProduct().getId(), l.getProduct().getName(), l.getProduct().getUnit(),
                    l.getBatches(), l.getPlannedQuantity(), l.getReceivedBy(), l.getReceivedAt(), l.getReceivedQuantity());
        }
    }

    public record Item(Long id, Long ingredientId, String ingredientName, String ingredientUnit,
                        BigDecimal requiredQuantity, BigDecimal availableQuantity, String originalIngredientName) {
        static Item from(ProductionRequestItem i) {
            return new Item(i.getId(), i.getIngredient().getId(), i.getIngredient().getName(),
                    i.getIngredient().getUnit(), i.getRequiredQuantity(), i.getIngredient().getQuantityInStock(),
                    i.getOriginalIngredientName());
        }
    }

    public static ProductionRequestDto from(ProductionRequest r) {
        return new ProductionRequestDto(
                r.getId(),
                r.getTitle(),
                r.getStatus().name(),
                r.getStatus().getLabel(),
                r.getCreatedBy(),
                r.getCreatedAt(),
                r.getDocumentDate(),
                r.getCollectedBy(),
                r.getCollectedAt(),
                r.getOneCReportNumber(),
                r.getOneCRequisitionNumber(),
                r.getOneCSyncError(),
                r.getLines().stream().map(Line::from).toList(),
                r.getItems().stream().map(Item::from).toList()
        );
    }
}
