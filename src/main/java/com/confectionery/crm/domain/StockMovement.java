package com.confectionery.crm.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Журнал ручных движений по складу: поступления и списания (см. StockService) — как сырья
 * (ingredient), так и готовой продукции (product). Ровно одно из двух полей заполнено —
 * какое именно, определяет тип движения.
 */
@Entity
@Table(name = "stock_movements")
@Getter
@Setter
@NoArgsConstructor
public class StockMovement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ingredient_id")
    private Ingredient ingredient;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    private Product product;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StockMovementType type;

    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    private String reason;

    /**
     * Ручное движение (зав. складом сам оформил через "Склад") vs автоматическое, порождённое
     * заявкой на производство (списание сырья при сборке / приход товара при оприходовании
     * строки — см. ProductionRequestService). Автоматические не показываются на страницах
     * "Поступления"/"Списание" (см. StockService.findMovements) — иначе список тонет в шуме
     * от каждой заявки; они и так видны в самой заявке.
     */
    @Column(nullable = false)
    private boolean manual = true;

    @Column(nullable = false)
    private String createdBy;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
