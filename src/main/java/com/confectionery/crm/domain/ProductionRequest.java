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
 * Заявка бухгалтера на выдачу сырья под производство партии товара: NEW (создана) ->
 * COLLECTED (зав. склада сырья собрал по рецепту и списал сырьё со своего склада) ->
 * RECEIVED (зав. склада ГП оприходовал фактически полученную готовую продукцию).
 * items — снимок рецепта на момент создания заявки (см. ProductionRequestItem), не
 * привязан к живому Recipe — правки рецептуры не задним числом не влияют на уже созданные заявки.
 */
@Entity
@Table(name = "production_requests")
@Getter
@Setter
@NoArgsConstructor
public class ProductionRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal batches;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ProductionRequestStatus status = ProductionRequestStatus.NEW;

    @Column(nullable = false)
    private String createdBy;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private String collectedBy;
    private LocalDateTime collectedAt;

    private String receivedBy;
    private LocalDateTime receivedAt;
    @Column(precision = 12, scale = 3)
    private BigDecimal receivedQuantity;

    @OneToMany(mappedBy = "request", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ProductionRequestItem> items = new ArrayList<>();

    public void addItem(ProductionRequestItem item) {
        item.setRequest(this);
        items.add(item);
    }
}
