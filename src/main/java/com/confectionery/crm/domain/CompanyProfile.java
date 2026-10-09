package com.confectionery.crm.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Синглтон-профиль компании (одна запись, id=1) — используется в шапке PDF-накладной и на
 * рекламной странице (templates/promo.html): там же время выдачи, текст «о нас» и реквизиты.
 */
@Entity
@Table(name = "company_profile")
@Getter
@Setter
@NoArgsConstructor
public class CompanyProfile {

    @Id
    private Long id = 1L;

    private String name;

    private String address;

    private String phone;

    private String email;

    /** Дни и часы выдачи заказов — только для рекламной страницы, например «Пн–Сб, 7:00–16:00». */
    private String pickupHours;

    /** Пара предложений «о нас» для рекламной страницы. */
    @Column(length = 2000)
    private String about;

    /** Юр. лицо и БИН/ИИН для подвала рекламной страницы, например «ТОО «…» · БИН …». */
    private String legalInfo;
}
