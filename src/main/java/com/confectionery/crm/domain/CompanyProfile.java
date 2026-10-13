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

    /** Ссылка на Instagram для рекламной страницы, например https://www.instagram.com/yug_sladosti. */
    private String instagramUrl;

    /** Ссылка на TikTok, на сайте выводится в одном пункте с Instagram. */
    private String tiktokUrl;

    /** Время работы для страницы /contacts: «Пн–Сб с 07:00 до 18:00, обед с 12:00 до 13:00». */
    private String workHours;

    /** Номер для звонков по вакансиям (страница /vacancies). */
    private String jobsPhone;

    /** Ворота производства на карте /contacts: «широта, долгота». Пусто — карты нет, кнопки ищут адрес. */
    private String mapPoint;

    /** Номер WhatsApp для кнопки на рекламной странице (ссылка wa.me собирается из цифр). */
    private String whatsappPhone;

    /**
     * Скрытые возможности магазина /shop (Настройки › Магазин), по умолчанию выключены: клиент может
     * указать день и время выдачи (необязательно) и выбрать способ оплаты. null — выключено.
     */
    private Boolean shopPickupChoice;
    private Boolean shopPaymentChoice;

    public boolean pickupChoiceOn() {
        return Boolean.TRUE.equals(shopPickupChoice);
    }

    public boolean paymentChoiceOn() {
        return Boolean.TRUE.equals(shopPaymentChoice);
    }

    /** Адрес до « (…)» для сайта, с неразрывными пробелами: строка не кончается на «г.», «ж/м», «ул.». */
    public String addressMain() {
        if (address == null || address.isBlank()) return null;
        int cut = address.indexOf(" (");
        return nbsp(cut > 0 ? address.substring(0, cut) : address);
    }

    /** Уточнение адреса в скобках — «(ул. Елебекова, 135а, Енбекшинский район)» — или null. */
    public String addressSub() {
        if (address == null) return null;
        int cut = address.indexOf(" (");
        return cut > 0 ? nbsp(address.substring(cut + 1)) : null;
    }

    private static String nbsp(String s) {
        return s.strip()
                .replaceAll("(^|[\\s(])(г\\.|ж/м|ул\\.|пр\\.|мкр\\.|д\\.)\\s+", "$1$2 ")
                .replaceAll(",\\s+(\\d)", ", $1")
                .replaceAll("\\s+(район)", " $1");
    }
}
