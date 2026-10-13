package com.confectionery.crm.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Журнал заказа: кто и когда менял статус, переносил выдачу, менял состав, отмечал оплату.
 * Пишется в OrderService при каждом действии (и из админки, и из приложения). У заказов, созданных
 * до появления журнала, история достраивается по отметкам времени заказа (см. OrderHistory).
 */
@Entity
@Table(name = "order_events", indexes = {
        @Index(name = "idx_order_events_order", columnList = "order_id"),
        @Index(name = "idx_order_events_status_at", columnList = "status, event_at")
})
@Getter
@Setter
@NoArgsConstructor
public class OrderEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OrderEventType type;

    /** Новый статус — для type = STATUS. */
    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private OrderStatus status;

    @Column(name = "event_at", nullable = false)
    private LocalDateTime at = LocalDateTime.now();

    /** Кто: ФИО сотрудника, «Покупатель на сайте», «Покупатель в WhatsApp», «1С». */
    @Column(length = 200)
    private String byName;

    /** Причина отмены, «было … → …» при переносе, что изменили в заказе. */
    @Column(length = 500)
    private String note;
}
