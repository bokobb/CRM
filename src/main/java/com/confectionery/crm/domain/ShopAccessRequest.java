package com.confectionery.crm.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Заявка на доступ к сайту клиентов (/shop) с номера, которого нет среди клиентов. Раньше
 * такой номер сразу становился новым клиентом — теперь админ получает push и решает в
 * приложении (экран «Клиенты»): разрешить — создаётся клиент (или находится уже заведённый
 * вручную с тем же номером), и дальше вход по этому номеру проходит сразу; отклонить — сайт
 * показывает «в доступе отказано».
 *
 * <p>Статус хранится строкой без enum — без CHECK-constraint от Hibernate, который потом
 * пришлось бы чинить вручную при добавлении статуса.</p>
 */
@Entity
@Table(name = "shop_access_requests")
@Getter
@Setter
@NoArgsConstructor
public class ShopAccessRequest {

    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String phone;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, length = 16)
    private String status = PENDING;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime decidedAt;

    /** Логин админа, принявшего решение. */
    private String decidedBy;

    /** Клиент, созданный/найденный при разрешении. */
    private Long clientId;
}
