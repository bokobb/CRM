package com.confectionery.crm.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Строка заявки: сколько конкретного сырья нужно выдать — снимок RecipeItem.quantity,
 * умноженный на запрошенное количество замесов (см. ProductionRequestService.create).
 */
@Entity
@Table(name = "production_request_items")
@Getter
@Setter
@NoArgsConstructor
public class ProductionRequestItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "request_id", nullable = false)
    private ProductionRequest request;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ingredient_id", nullable = false)
    private Ingredient ingredient;

    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal requiredQuantity;
}
