package com.confectionery.crm.api.dto;

import java.util.List;

public record TechCardRequest(
        String shelfLife,
        String storageConditions,
        String packaging,
        String qualityLook,
        String qualityColor,
        String qualityCrumb,
        String qualityTaste,
        List<TechCardDto.Stage> stages
) {
}
