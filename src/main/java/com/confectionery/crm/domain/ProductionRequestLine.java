package com.confectionery.crm.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Одна позиция заявки: конкретный товар + сколько замесов его рецепта запросили. Оприходуется
 * независимо от остальных строк той же заявки — фактический выход у каждого товара свой.
 */
@Entity
@Table(name = "production_request_lines")
@Getter
@Setter
@NoArgsConstructor
public class ProductionRequestLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "request_id", nullable = false)
    private ProductionRequest request;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    /** Сколько раз повторить рецепт — то, что реально ввёл пользователь при создании заявки. */
    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal batches;

    /**
     * Сколько готовой продукции ОЖИДАЕТСЯ получить по этой строке, в единицах товара (шт/кг) —
     * оценка {@code batches × recipe.yieldQuantity}. Не обязательство: реальный выход с одного
     * замеса гуляет, фактическое количество вводится отдельно при оприходовании этой строки.
     */
    @Column(precision = 12, scale = 3)
    private BigDecimal plannedQuantity;

    private String receivedBy;
    private LocalDateTime receivedAt;
    @Column(precision = 12, scale = 3)
    private BigDecimal receivedQuantity;

    public boolean isReceived() {
        return receivedQuantity != null;
    }

    /**
     * Сколько не хватает до нормы (plannedQuantity − receivedQuantity) — 0, если ещё не
     * оприходовано, план не задан, или оприходовано >= плана. Красным в UI вместо зелёного —
     * см. isInsufficient.
     */
    public BigDecimal getShortfall() {
        if (receivedQuantity == null || plannedQuantity == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal diff = plannedQuantity.subtract(receivedQuantity);
        return diff.signum() > 0 ? diff : BigDecimal.ZERO;
    }

    /** true — оприходовано МЕНЬШЕ нормы (см. getShortfall), в UI это красным, иначе зелёным. */
    public boolean isInsufficient() {
        return getShortfall().signum() > 0;
    }
}
