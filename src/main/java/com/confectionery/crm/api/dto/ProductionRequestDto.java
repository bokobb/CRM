package com.confectionery.crm.api.dto;

import com.confectionery.crm.domain.ProductionRequest;
import com.confectionery.crm.domain.ProductionRequestItem;
import com.confectionery.crm.domain.ProductionRequestLine;
import com.confectionery.crm.service.ProductionRequestService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record ProductionRequestDto(
        Long id,
        String title,
        String status,
        String statusLabel,
        String createdBy,
        LocalDateTime createdAt,
        LocalDate documentDate,
        String collectedBy,
        LocalDateTime collectedAt,
        String oneCReportNumber,
        String oneCRequisitionNumber,
        String oneCSyncError,
        boolean requisitionCompleted,
        List<Line> lines,
        List<Item> items,
        MassSummary massSummary
) {
    /**
     * Сверка нормы по общей массе вместо штук — для товаров вроде Чак-чака, где несколько
     * фасовок режутся из одного замеса (см. {@link ProductionRequestService#massSummary}).
     * Null, если весом (netWeightGrams) отмечено меньше двух позиций заявки.
     */
    public record MassSummary(BigDecimal plannedKg, BigDecimal actualKg, BigDecimal deviationKg,
                               boolean insufficient, boolean allWeightedReceived,
                               String referenceProductName, BigDecimal deviationPiecesAbs) {
        static MassSummary from(ProductionRequestService.MassSummary s) {
            return s == null ? null : new MassSummary(s.getPlannedKg(), s.getActualKg(), s.getDeviationKg(),
                    s.isInsufficient(), s.allWeightedReceived(), s.referenceProductName(), s.getDeviationPiecesAbs());
        }
    }

    public record Line(
            Long id, Long productId, String productName, String productUnit,
            BigDecimal batches, BigDecimal plannedQuantity,
            String receivedBy, LocalDateTime receivedAt, BigDecimal receivedQuantity,
            boolean insufficient
    ) {
        /**
         * {@code insufficient} — построчная норма (см. {@link ProductionRequestLine#isInsufficient()}),
         * НО если товар участвует в сверке по общей массе (задан {@code netWeightGrams}, см.
         * {@link ProductionRequestService#massSummary}) и сама сверка показывает, что нормы
         * ХВАТАЕТ — гасим построчную "недостаточно": рабочие могли просто перепутать пропорции
         * между фасовками при нарезке, по массе всё в порядке (см. коммит "Сверка нормы по общей
         * массе..."). Не наоборот — если по массе как раз недостача, а конкретно эта строка сама
         * по себе свою норму выполнила, её отдельно красным не делаем (аггрегированная недостача
         * не про эту позицию персонально).
         */
        static Line from(ProductionRequestLine l, ProductionRequestService.MassSummary massSummary) {
            boolean weighted = massSummary != null && l.getProduct().getNetWeightGrams() != null;
            boolean insufficient = l.isInsufficient() && !(weighted && !massSummary.isInsufficient());
            return new Line(l.getId(), l.getProduct().getId(), l.getProduct().getName(), l.getProduct().getUnit(),
                    l.getBatches(), l.getPlannedQuantity(), l.getReceivedBy(), l.getReceivedAt(), l.getReceivedQuantity(),
                    insufficient);
        }
    }

    public record Item(Long id, Long ingredientId, String ingredientName, String ingredientUnit,
                        BigDecimal requiredQuantity, BigDecimal availableQuantity, String originalIngredientName) {
        static Item from(ProductionRequestItem i) {
            return new Item(i.getId(), i.getIngredient().getId(), i.getIngredient().getName(),
                    i.getIngredient().getUnit(), i.getRequiredQuantity(), i.getIngredient().getQuantityInStock(),
                    i.getOriginalIngredientName());
        }
    }

    /**
     * {@code massSummary} считается в {@link ProductionRequestService#massSummary} и передаётся
     * сюда готовым — этот record не имеет доступа к сервисам/репозиториям (нужны для поиска
     * товара-эталона группы, см. Product.massReferenceProduct), поэтому вызывающая сторона
     * (см. ProductionRequestApiController) считает его сама.
     */
    public static ProductionRequestDto from(ProductionRequest r, ProductionRequestService.MassSummary massSummary) {
        return new ProductionRequestDto(
                r.getId(),
                r.getTitle(),
                r.getStatus().name(),
                r.getStatus().getLabel(),
                r.getCreatedBy(),
                r.getCreatedAt(),
                r.getDocumentDate(),
                r.getCollectedBy(),
                r.getCollectedAt(),
                r.getOneCReportNumber(),
                r.getOneCRequisitionNumber(),
                r.getOneCSyncError(),
                r.isRequisitionCompleted(),
                r.getLines().stream().map(l -> Line.from(l, massSummary)).toList(),
                r.getItems().stream().map(Item::from).toList(),
                MassSummary.from(massSummary)
        );
    }
}
