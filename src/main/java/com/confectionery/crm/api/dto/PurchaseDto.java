package com.confectionery.crm.api.dto;

import com.confectionery.crm.domain.Purchase;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record PurchaseDto(
        Long id,
        Long ingredientId,
        String ingredientName,
        String ingredientUnit,
        BigDecimal quantity,
        String supplier,
        BigDecimal totalPrice,
        LocalDate purchaseDate,
        String createdBy,
        LocalDateTime createdAt
) {
    public static PurchaseDto from(Purchase p) {
        return new PurchaseDto(
                p.getId(), p.getIngredient().getId(), p.getIngredient().getName(), p.getIngredient().getUnit(),
                p.getQuantity(), p.getSupplier(), p.getTotalPrice(), p.getPurchaseDate(), p.getCreatedBy(), p.getCreatedAt()
        );
    }
}
