package com.confectionery.crm.api.dto;

import com.confectionery.crm.domain.ProductionRequest;
import com.confectionery.crm.domain.ProductionRequestLine;
import com.confectionery.crm.service.ProductionRequestService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

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
        boolean requisitionCompleted
) {
    public static ProductionRequestSummaryDto from(ProductionRequest r) {
        BigDecimal total = r.getLines().stream().map(l -> l.getBatches()).reduce(BigDecimal.ZERO, BigDecimal::add);
        // Та же поблажка, что и в карточке заявки (см. ProductionRequestDto.Line): если товар
        // участвует в сверке по общей массе и по массе нормы хватает — построчную недостачу
        // не показываем, рабочие просто перепутали пропорции между фасовками при нарезке.
        boolean massNormFulfilled = ProductionRequestService.massNormFulfilled(r);
        String shortfallText = r.getLines().stream()
                .filter(l -> l.isInsufficient()
                        && !(massNormFulfilled && l.getProduct().getNetWeightGrams() != null))
                .map(l -> l.getProduct().getName() + " — " + l.getShortfall().stripTrailingZeros().toPlainString()
                        + " " + safeUnit(l))
                .collect(Collectors.joining(", "));
        return new ProductionRequestSummaryDto(
                r.getId(), r.getTitle(), r.getLines().stream().map(l -> l.getProduct().getName()).toList(), total,
                r.getStatus().name(), r.getStatus().getLabel(), r.getCreatedBy(), r.getCreatedAt(), r.getDocumentDate(),
                !shortfallText.isEmpty(), shortfallText.isEmpty() ? null : shortfallText, r.isRequisitionCompleted()
        );
    }

    private static String safeUnit(ProductionRequestLine l) {
        String unit = l.getProduct().getUnit();
        return unit == null || unit.isBlank() ? "шт" : unit;
    }
}
