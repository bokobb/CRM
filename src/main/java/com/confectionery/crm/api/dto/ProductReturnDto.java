package com.confectionery.crm.api.dto;

import com.confectionery.crm.domain.ProductReturn;
import com.confectionery.crm.domain.ProductReturnItem;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record ProductReturnDto(
        Long id,
        Long clientId,
        String clientName,
        Long orderId,
        LocalDate returnDate,
        String reason,
        String createdBy,
        LocalDateTime createdAt,
        BigDecimal totalSum,
        String oneCDocumentNumber,
        String oneCSyncError,
        List<Item> items
) {
    public record Item(Long id, Long productId, String productName, String productUnit,
                       BigDecimal quantity, BigDecimal price) {
        static Item from(ProductReturnItem i) {
            return new Item(i.getId(), i.getProduct().getId(), i.getProduct().getName(),
                    i.getProduct().getUnit(), i.getQuantity(), i.getPrice());
        }
    }

    public static ProductReturnDto from(ProductReturn r) {
        return new ProductReturnDto(
                r.getId(),
                r.getClient().getId(),
                r.getClient().getName(),
                r.getOrder() != null ? r.getOrder().getId() : null,
                r.getReturnDate(),
                r.getReason(),
                r.getCreatedBy(),
                r.getCreatedAt(),
                r.getTotalSum(),
                r.getOneCDocumentNumber(),
                r.getOneCSyncError(),
                r.getItems().stream().map(Item::from).toList()
        );
    }
}
