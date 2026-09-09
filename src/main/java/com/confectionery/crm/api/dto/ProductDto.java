package com.confectionery.crm.api.dto;

import com.confectionery.crm.domain.Product;

import java.math.BigDecimal;

public record ProductDto(
        Long id,
        String name,
        String category,
        String group,
        String description,
        BigDecimal price,
        String unit,
        BigDecimal quantityInStock,
        String imageUrl,
        boolean semiFinished
) {
    public static ProductDto from(Product p) {
        return new ProductDto(p.getId(), p.getName(), p.getCategory(), p.getGroup(), p.getDescription(), p.getPrice(), p.getUnit(),
                p.getQuantityInStock(), p.getImageUrl(), p.isSemiFinished());
    }
}
