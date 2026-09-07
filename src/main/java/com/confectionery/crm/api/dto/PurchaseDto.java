package com.confectionery.crm.api.dto;

import com.confectionery.crm.domain.Purchase;
import com.confectionery.crm.domain.PurchaseItem;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record PurchaseDto(
        Long id,
        Long counterpartyId,
        String counterpartyName,
        LocalDate purchaseDate,
        String createdBy,
        LocalDateTime createdAt,
        String imageUrl,
        String oneCDocumentNumber,
        String oneCSyncError,
        List<Item> items
) {
    public record Item(Long id, Long ingredientId, String ingredientName, String ingredientUnit,
                        BigDecimal quantity, BigDecimal totalPrice) {
        static Item from(PurchaseItem i) {
            return new Item(i.getId(), i.getIngredient().getId(), i.getIngredient().getName(),
                    i.getIngredient().getUnit(), i.getQuantity(), i.getTotalPrice());
        }
    }

    public static PurchaseDto from(Purchase p) {
        return new PurchaseDto(
                p.getId(),
                p.getCounterparty().getId(),
                p.getCounterparty().getName(),
                p.getPurchaseDate(),
                p.getCreatedBy(),
                p.getCreatedAt(),
                p.getImageUrl(),
                p.getOneCDocumentNumber(),
                p.getOneCSyncError(),
                p.getItems().stream().map(Item::from).toList()
        );
    }
}
