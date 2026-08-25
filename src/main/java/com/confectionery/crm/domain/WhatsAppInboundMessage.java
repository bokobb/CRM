package com.confectionery.crm.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Сырое сообщение-заказ из WhatsApp вместе с разбором ИИ (см. ClaudeOrderParserService).
 * Если ИИ уверенно сопоставил все позиции с каталогом — заказ создаётся сразу и запись
 * остаётся в истории со статусом RESOLVED. Если что-то осталось неясным — запись висит
 * PENDING на экране /whatsapp-inbox, пока администратор не подберёт товар вручную.
 */
@Entity
@Table(name = "whatsapp_inbound_messages")
@Getter
@Setter
@NoArgsConstructor
public class WhatsAppInboundMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "client_id", nullable = false)
    private Client client;

    @Column(nullable = false)
    private String fromPhone;

    @Column(nullable = false, length = 2000)
    private String rawText;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WhatsAppInboundStatus status = WhatsAppInboundStatus.PENDING;

    /** Заполняется после успешного создания заказа (сразу или после ручного уточнения). */
    private Long resolvedOrderId;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @OneToMany(mappedBy = "message", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<WhatsAppInboundItem> items = new ArrayList<>();

    public void addItem(WhatsAppInboundItem item) {
        item.setMessage(this);
        items.add(item);
    }

    public boolean hasUnmatchedItems() {
        return items.stream().anyMatch(i -> i.getProduct() == null);
    }
}
