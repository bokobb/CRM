package com.confectionery.crm.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Снимок состава сырья заявки — история правок после того, как сырьё уже выдано работникам
 * (см. ProductionRequestService.recordRevision). {@code issued = true} — этот состав физически
 * выдан на руки (сборка или «Рассчитались» после выдачи/возврата разницы); от последнего такого
 * снимка считается разница с текущим составом: не хватает — довыдать, лишнее — вернуть на склад.
 * {@code issued = false} — просто правка состава, в историю.
 */
@Entity
@Table(name = "production_request_revisions")
@Getter
@Setter
@NoArgsConstructor
public class ProductionRequestRevision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "request_id", nullable = false)
    private ProductionRequest request;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(nullable = false)
    private String createdBy;

    /** «Собрано», «правка позиций», «добавлено: Мука» и т.п. */
    @Column(length = 500)
    private String note;

    @Column(nullable = false)
    private boolean issued;

    @ElementCollection
    @CollectionTable(name = "production_request_revision_items", joinColumns = @JoinColumn(name = "revision_id"))
    @OrderColumn(name = "item_order")
    private List<Item> items = new ArrayList<>();

    /** Замесы по товарам на момент снимка — чтобы видеть «замес 3 → 4». У снимков до 05.10 пусто. */
    @ElementCollection
    @CollectionTable(name = "production_request_revision_lines", joinColumns = @JoinColumn(name = "revision_id"))
    @OrderColumn(name = "line_order")
    private List<Line> lines = new ArrayList<>();

    /** Одна позиция снимка — название/единица копируются, чтобы история не зависела от справочника. */
    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    public static class Item {
        @Column(nullable = false)
        private Long ingredientId;
        @Column(nullable = false)
        private String ingredientName;
        private String unit;
        @Column(nullable = false, precision = 12, scale = 3)
        private BigDecimal quantity;

        public Item(Long ingredientId, String ingredientName, String unit, BigDecimal quantity) {
            this.ingredientId = ingredientId;
            this.ingredientName = ingredientName;
            this.unit = unit;
            this.quantity = quantity;
        }
    }

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    public static class Line {
        @Column(nullable = false)
        private Long productId;
        @Column(nullable = false)
        private String productName;
        @Column(nullable = false, precision = 12, scale = 3)
        private BigDecimal batches;

        public Line(Long productId, String productName, BigDecimal batches) {
            this.productId = productId;
            this.productName = productName;
            this.batches = batches;
        }
    }
}
