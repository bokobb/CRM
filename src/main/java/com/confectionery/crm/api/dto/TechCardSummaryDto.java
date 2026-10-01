package com.confectionery.crm.api.dto;

import java.math.BigDecimal;

/** Карточка товара в списке техкарт (экран «Производство»). */
public record TechCardSummaryDto(
        Long productId,
        String name,
        String group,
        String unit,
        String imageUrl,
        boolean semiFinished,
        BigDecimal yieldQuantity,
        int ingredientCount,
        int stageCount,
        int ccpCount,
        boolean draft
) {
}
