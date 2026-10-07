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

    /** Обычно момент создания; у списания из формы — выбранная дата (см. StockService.writeOffBatch). */
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    /**
     * Когда движение попало в документ 1С: у движений с {@link #document} — когда документ
     * формы проведён в 1С (см. OneCStockDocumentSyncService). Без документа заполнено только у
     * старых поступлений, выгруженных «Общим оприходованием» (функция удалена 07.10). Null —
     * ещё не выгружено.
     */
    private LocalDateTime oneCSyncedAt;

    /** Номер документа 1С ("Оприходование"/"Списание товаров"), в который попало это движение. */
    private String oneCDocumentNumber;

    /**
     * Документ формы «Поступление»/«Списание», к которому относится строка (с 06.10) — он и
     * уходит в 1С, см. StockDocument. У старых движений и у автоматических — null.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id")
    private StockDocument document;
}
