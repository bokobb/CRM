package com.confectionery.crm.api.dto;

import com.confectionery.crm.domain.ProductionRequestRevision;
import com.confectionery.crm.service.ProductionRequestService;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Состав заявки против выданного работникам — см. ProductionRequestService.compositionDiff.
 * {@code issuedAt == null} — сырьё ещё не выдавали, разницы нет.
 */
public record ProductionCompositionDiffDto(
        Long requestId,
        String title,
        String statusLabel,
        LocalDateTime issuedAt,
        String issuedBy,
        List<Row> rows,
        List<Revision> history
) {
    /** diff = сейчас − выдано: плюс — довыдать, минус — вернуть на склад. */
    public record Row(String ingredientName, String unit, BigDecimal issued, BigDecimal current, BigDecimal diff) {
    }

    public record Revision(LocalDateTime createdAt, String createdBy, String note, boolean issued, List<Item> items) {
    }

    public record Item(String ingredientName, String unit, BigDecimal quantity) {
    }

    public static ProductionCompositionDiffDto from(ProductionRequestService.CompositionDiff d) {
        ProductionRequestRevision baseline = d.baseline();
        return new ProductionCompositionDiffDto(
                d.request().getId(),
                d.request().getTitle(),
                d.request().getStatus().getLabel(),
                baseline == null ? null : baseline.getCreatedAt(),
                baseline == null ? null : baseline.getCreatedBy(),
                d.rows().stream().map(r -> new Row(r.ingredientName(), r.unit(), r.issued(), r.current(), r.diff())).toList(),
                d.history().stream().map(h -> new Revision(h.getCreatedAt(), h.getCreatedBy(), h.getNote(), h.isIssued(),
                        h.getItems().stream().map(i -> new Item(i.getIngredientName(), i.getUnit(), i.getQuantity())).toList()))
                        .toList()
        );
    }
}
