package com.confectionery.crm.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record ProductRequest(
        @NotBlank String name,
        String category,
        String group,
        String description,
        @NotNull BigDecimal price,
        String unit,
        String imageUrl
) {
}
