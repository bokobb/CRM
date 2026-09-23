package com.confectionery.crm.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/** Одна позиция возврата — см. {@link ProductReturn}. */
@Entity
@Table(name = "product_return_items")
@Getter
@Setter
@NoArgsConstructor
public class ProductReturnItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_return_id", nullable = false)
    private ProductReturn productReturn;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    /** Цена возврата — по умолчанию цена клиента (см. ClientPriceService.resolvePrice), можно поправить вручную. */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;
}
