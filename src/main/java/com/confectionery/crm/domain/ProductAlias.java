package com.confectionery.crm.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Как клиенты в WhatsApp называют товар помимо официального названия ("розочка" для
 * "Маффины", "барни" и т.п.) — подмешивается в промпт ClaudeOrderParserService, чтобы
 * распознавание заказов "училось" на реальных сообщениях клиентов. Ведётся вручную
 * администратором в карточке товара.
 */
@Entity
@Table(name = "product_aliases")
@Getter
@Setter
@NoArgsConstructor
public class ProductAlias {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @NotBlank
    @Column(nullable = false)
    private String alias;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
