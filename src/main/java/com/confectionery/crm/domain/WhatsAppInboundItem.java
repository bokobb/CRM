package com.confectionery.crm.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Одна позиция из сообщения WhatsApp: как клиент назвал товар (без количества) и сколько
 * попросил. {@code product == null}, пока Claude не сопоставил её с каталогом уверенно —
 * тогда администратор выбирает товар вручную на экране /whatsapp-inbox, и этот выбор
 * запоминается как ProductAlias (см. WhatsAppOrderIntakeService.resolvePending).
 */
@Entity
@Table(name = "whatsapp_inbound_items")
@Getter
@Setter
@NoArgsConstructor
public class WhatsAppInboundItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "message_id", nullable = false)
    private WhatsAppInboundMessage message;

    @Column(nullable = false)
    private String rawProductText;

    @NotNull
    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    private Product product;
}
