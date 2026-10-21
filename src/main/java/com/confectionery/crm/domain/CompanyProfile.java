package com.confectionery.crm.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /** Год основания — на сайте «на рынке с …» и в разметке для поисковиков (foundingDate). */
    private Integer foundedYear;

    /**
     * Карточки компании на других сайтах — 2ГИС, Яндекс Карты, Google Карты, маркетплейсы, по одной
     * ссылке в строке. Поисковики (и ИИ-ответы Google/Яндекса) по ним связывают сайт с этими карточками.
     */
    @Column(length = 2000)
    private String externalLinks;

    /**
     * Скрытые возможности магазина /shop (Настройки › Магазин), по умолчанию выключены: клиент может
     * указать день и время выдачи (необязательно) и выбрать способ оплаты. null — выключено.
     */
    private Boolean shopPickupChoice;
    private Boolean shopPaymentChoice;

    /**
     * Окна выдачи (Настройки › Окна выдачи): первое окно с часа, последнее — до часа, сколько заказов
     * успевают выдать в одном окне; null — как в crm.pickup.* (application.properties). См. PickupSlotService.
     */
    private Integer pickupFirstHour;
    private Integer pickupLastHour;
    private Integer pickupCapacity;

    /** Дни выдачи пн…вс строкой из 7 знаков: «1111110» — без воскресенья; null — каждый день. */
    @Column(length = 7)
    private String pickupDays;

    public boolean pickupDayOn(java.time.DayOfWeek day) {
        return pickupDays == null || pickupDays.length() != 7 || pickupDays.charAt(day.getValue() - 1) == '1';
    }

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

    private static final Pattern TAX_ID = Pattern.compile("(?:БИН|ИИН)\\s*[:№]?\\s*(\\d{12})");

    /** Первая строка реквизитов — для подвала сайта («ИП … · ИИН …»); банк и счёт ниже не выводятся. */
    public String legalLine() {
        if (legalInfo == null || legalInfo.isBlank()) return null;
        return legalInfo.strip().lines().findFirst().map(String::strip).orElse(null);
    }

    /** Юр. название из первой строки реквизитов без БИН/ИИН: «ИП Иванов И. И.». */
    public String legalName() {
        String line = legalLine();
        if (line == null) return null;
        String name = TAX_ID.matcher(line).replaceAll("").replaceAll("[\\s,;·|–—-]+$", "").strip();
        return name.isEmpty() ? null : name;
    }

    /** БИН или ИИН (12 цифр) из реквизитов, иначе null. */
    public String taxId() {
        if (legalInfo == null) return null;
        Matcher m = TAX_ID.matcher(legalInfo);
        return m.find() ? m.group(1) : null;
    }

    /** Почта для сайта — только настоящий адрес (с «@»), иначе null. */
    public String siteEmail() {
        return email != null && email.strip().matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+") ? email.strip() : null;
    }

    /** Ссылки из «Компания в интернете» — только http(s), без повторов. */
    public List<String> externalLinkList() {
        if (externalLinks == null) return List.of();
        return Arrays.stream(externalLinks.split("[\\s,;]+")).map(String::strip)
                .filter(u -> u.startsWith("https://") || u.startsWith("http://")).distinct().toList();
    }

    private static String nbsp(String s) {
        return s.strip()
                .replaceAll("(^|[\\s(])(г\\.|ж/м|ул\\.|пр\\.|мкр\\.|д\\.)\\s+", "$1$2 ")
                .replaceAll(",\\s+(\\d)", ", $1")
                .replaceAll("\\s+(район)", " $1");
    }
}
