package com.confectionery.crm.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Одна позиция закупки — конкретное сырьё + количество (см. {@link Purchase}, один контрагент
 * может привезти сразу несколько разных товаров за один раз). Каждая позиция — своё
 * поступление на склад (см. StockService.receive/PurchaseService.create), поэтому у каждой
 * свой {@link StockMovement} для трассируемости остатка.
 */
@Entity
@Table(name = "purchase_items")
@Getter
@Setter
@NoArgsConstructor
public class PurchaseItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "purchase_id", nullable = false)
    private Purchase purchase;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ingredient_id", nullable = false)
    private Ingredient ingredient;

    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    /** Необязательно — не всегда известна/указывается цена по каждой позиции отдельно. */
    @Column(precision = 12, scale = 2)
    private BigDecimal totalPrice;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stock_movement_id", nullable = false, unique = true)
    private StockMovement stockMovement;
}
