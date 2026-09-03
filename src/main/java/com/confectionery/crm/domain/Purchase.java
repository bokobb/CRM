package com.confectionery.crm.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Закупка сырья у поставщика — отдельный, более подробный учёт (кто поставщик, сколько
 * заплатили, когда), поверх обычного прихода на склад. Само движение остатка делает
 * StockService.receive (см. PurchaseService.create) — Purchase лишь хранит закупочные
 * подробности и ссылается на созданную запись StockMovement для трассируемости.
 */
@Entity
@Table(name = "purchases")
@Getter
@Setter
@NoArgsConstructor
public class Purchase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ingredient_id", nullable = false)
    private Ingredient ingredient;

    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    @Column(nullable = false)
    private String supplier;

    @Column(precision = 12, scale = 2)
    private BigDecimal totalPrice;

    @Column(nullable = false)
    private LocalDate purchaseDate;

    @Column(nullable = false)
    private String createdBy;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stock_movement_id", nullable = false, unique = true)
    private StockMovement stockMovement;
}
