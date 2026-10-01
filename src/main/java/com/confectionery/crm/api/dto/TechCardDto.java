package com.confectionery.crm.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Полная техкарта: товар + рецептура (сырьё на замес из Recipe, цена — последняя закупка) +
 * поля самой техкарты (TechCard). Расчёт партии под план выпуска делает клиент.
 */
public record TechCardDto(
        Long productId,
        String name,
        String group,
        String unit,
        String imageUrl,
        BigDecimal netWeightGrams,
        BigDecimal salePrice,
        BigDecimal yieldQuantity,
        List<Item> items,
        String shelfLife,
        String storageConditions,
        String packaging,
        String qualityLook,
        String qualityColor,
        String qualityCrumb,
        String qualityTaste,
        List<Stage> stages,
        boolean draft
) {
    /** unitPrice — ₸ за единицу сырья (unit) по последней закупке; null — закупок с ценой не было. */
    public record Item(Long ingredientId, String name, String unit, BigDecimal quantity,
                       BigDecimal unitPrice, LocalDate priceDate) {
    }

    public record Stage(String title, String description, String temperature, String duration, boolean ccp) {
    }
}
