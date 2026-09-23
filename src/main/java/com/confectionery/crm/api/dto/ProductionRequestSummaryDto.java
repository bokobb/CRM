package com.confectionery.crm.api.dto;

import com.confectionery.crm.domain.ProductionRequest;
import com.confectionery.crm.domain.ProductionRequestLine;
import com.confectionery.crm.service.ProductionRequestService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Для списка заявок — без сырьевых позиций, чтобы не гонять лишние данные по сети. */
public record ProductionRequestSummaryDto(
        Long id,
        String title,
        List<String> productNames,
        BigDecimal totalBatches,
        String status,
        String statusLabel,
        String createdBy,
        LocalDateTime createdAt,
        LocalDate documentDate,
        /** Хотя бы одна строка оприходована меньше нормы — карточка в списке красная. */
        boolean hasShortfall,
        /** "Пряник — 10 шт, Торт — 5 шт" — что именно не дооприходовано; null, если хватает. */
        String shortfallText,
        /** Требование-накладная проведена в 1С кнопкой "Завершить" — см. ProductionRequest.requisitionCompleted. */
        boolean requisitionCompleted,
        /** Состав менялся после выдачи работникам, разницу ещё не выдали/не вернули. */
        boolean compositionDiffPending
) {
    /** Эталон сверки по массе — самый крупный товар заявки (без запроса к базе). */
    public static ProductionRequestSummaryDto from(ProductionRequest r) {
        return from(r, ProductionRequestService.massSummaryWithoutLookup(r));
    }

    /**
     * Как в карточке заявки (см. ProductionRequestDto.Line): фасовки из одного замеса (задан вес
     * 1 шт) построчно не сверяются — рабочие могли перепутать пропорции при нарезке. Их
     * недостача — одна по общей массе, в штуках эталона: "Чак чак Б — 8.5 шт".
     */
    public static ProductionRequestSummaryDto from(ProductionRequest r, ProductionRequestService.MassSummary mass) {
        BigDecimal total = r.getLines().stream().map(l -> l.getBatches()).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<String> shortfalls = new ArrayList<>(r.getLines().stream()
                .filter(l -> l.isInsufficient() && !(mass != null && l.getProduct().getNetWeightGrams() != null))
                .map(l -> l.getProduct().getName() + " — " + l.getShortfall().stripTrailingZeros().toPlainString()
                        + " " + safeUnit(l))
                .toList());
        if (mass != null && mass.allWeightedReceived() && mass.isInsufficient()) {
            shortfalls.add(mass.referenceProductName() + " — "
                    + mass.getDeviationPiecesAbs().stripTrailingZeros().toPlainString() + " шт");
        }
        String shortfallText = String.join(", ", shortfalls);
        return new ProductionRequestSummaryDto(
                r.getId(), r.getTitle(), r.getLines().stream().map(l -> l.getProduct().getName()).toList(), total,
                r.getStatus().name(), r.getStatus().getLabel(), r.getCreatedBy(), r.getCreatedAt(), r.getDocumentDate(),
                !shortfallText.isEmpty(), shortfallText.isEmpty() ? null : shortfallText, r.isRequisitionCompleted(),
                r.isCompositionDiffPending()
        );
    }

    private static String safeUnit(ProductionRequestLine l) {
        String unit = l.getProduct().getUnit();
        return unit == null || unit.isBlank() ? "шт" : unit;
    }
}
