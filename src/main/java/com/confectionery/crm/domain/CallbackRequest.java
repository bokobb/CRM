package com.confectionery.crm.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Заявка «Перезвоните мне» с сайта (главная и /contacts, POST /shop/callback). Отрабатывается в приложении:
 * «Отклики и заявки» › «Заявки» (ADMIN, ACCOUNTANT, SALES). Статус строкой без enum — как в ShopAccessRequest.
 */
@Entity
@Table(name = "callback_requests")
@Getter
@Setter
@NoArgsConstructor
public class CallbackRequest {

    public static final String NEW = "NEW";
    public static final String CONTACTED = "CONTACTED";
    public static final String ORDERED = "ORDERED";
    public static final String REJECTED = "REJECTED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 100)
    private String name;

    @Column(nullable = false, length = 40)
    private String phone;

    /** «Ваш бизнес»: магазин, кафе, столовая… */
    @Column(length = 100)
    private String business;

    @Column(nullable = false, length = 16)
    private String status = NEW;

    /** Комментарий сотрудника. */
    @Column(columnDefinition = "TEXT")
    private String note;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime handledAt;

    /** ФИО сотрудника, который последним менял статус. */
    @Column(length = 120)
    private String handledBy;
}
