package com.confectionery.crm.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Журнал автоматических уведомлений (клиенту или персоналу).
 * Реальная отправка (email/SMS/Telegram) инкапсулирована в NotificationService —
 * сейчас реализован логирующий канал, отправка через email включается свойством
 * crm.notifications.email-enabled.
 */
@Entity
@Table(name = "notifications")
@Getter
@Setter
@NoArgsConstructor
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String recipient;

    @Column(nullable = false, length = 2000)
    private String message;

    @Column(nullable = false)
    private String channel; // LOG, EMAIL

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id")
    private Order order;

    @Column(nullable = false)
    private boolean sent = false;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
