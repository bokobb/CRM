package com.confectionery.crm.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Индивидуальная цена товара для конкретного клиента-дистрибьютора — переопределяет Product.price. */
@Entity
@Table(name = "client_prices", uniqueConstraints = @UniqueConstraint(columnNames = {"client_id", "product_id"}))
@Getter
@Setter
@NoArgsConstructor
public class ClientPrice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "client_id", nullable = false)
    private Client client;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @NotNull
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Column(nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();
}
