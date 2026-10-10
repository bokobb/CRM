package com.confectionery.crm.api.dto;

import com.confectionery.crm.domain.Product;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

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
        boolean semiFinished,
        String oneCDepartmentKey,
        BigDecimal netWeightGrams,
        boolean massReferenceProduct,
        boolean hiddenFromClients,
        LocalDate hiddenFromClientsUntil,
        boolean visibleToClients,
        BigDecimal unitWeightKg,
        BigDecimal unitsPerBox,
        BigDecimal boxVolumeM3,
        BigDecimal unitsPerBox2,
        BigDecimal boxVolume2M3,
        String oneCCode,
        List<PhotoDto> photos
) {
    /** Фото альбома товара по порядку, первое — обложка (= imageUrl), см. ProductPhotoService. */
    public record PhotoDto(Long id, String url, Integer width, Integer height) {}

    public static ProductDto from(Product p) {
        return new ProductDto(p.getId(), p.getName(), p.getCategory(), p.getGroup(), p.getDescription(), p.getPrice(), p.getUnit(),
                p.getQuantityInStock(), p.getImageUrl(), p.isSemiFinished(), p.getOneCDepartmentKey(), p.getNetWeightGrams(),
                p.isMassReferenceProduct(), p.isHiddenFromClients(), p.getHiddenFromClientsUntil(), p.isVisibleToClients(),
                p.getUnitWeightKg(), p.getUnitsPerBox(), p.getBoxVolumeM3(),
                p.getUnitsPerBox2(), p.getBoxVolume2M3(), p.getOneCCode(),
                p.getPhotos().stream().map(ph -> new PhotoDto(ph.getId(), ph.getUrl(), ph.getWidth(), ph.getHeight())).toList());
    }
}
