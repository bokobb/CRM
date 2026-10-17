package com.confectionery.crm.service;

import com.confectionery.crm.domain.CompanyProfile;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Для поисковиков (17.10): разметка schema.org «Кондитерская» на главной и в «Контактах» — из
 * «Профиля компании», ничего не хардкодим; коды подтверждения Google Search Console / Яндекс
 * Вебмастера — из backend.env (пусто — мета-тега нет).
 */
@Service
@RequiredArgsConstructor
public class SeoService {

    /** Публичные страницы сайта для sitemap.xml: путь → как часто меняется. */
    public static final Map<String, String> PAGES = Map.of(
            "/", "daily", "/shop", "weekly", "/contacts", "monthly", "/vacancies", "weekly");

    private static final Pattern HOURS = Pattern.compile("(Пн|Вт|Ср|Чт|Пт|Сб|Вс)\\s*[–—-]\\s*(Пн|Вт|Ср|Чт|Пт|Сб|Вс)"
            + "\\D*?(\\d{1,2}:\\d{2})\\D+?(\\d{1,2}:\\d{2})");
    private static final List<String> DAYS_RU = List.of("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс");
    private static final List<String> DAYS_EN = List.of("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su");

    private final CompanyProfileService companyProfileService;
    private static final ObjectMapper JSON = new ObjectMapper();

    @Value("${crm.seo.google-verification:}")
    private String googleVerification;

    @Value("${crm.seo.yandex-verification:}")
    private String yandexVerification;

    /** То, что кладём в модель страниц сайта под именем "seo". */
    public record Seo(String baseUrl, String jsonLd, String googleVerification, String yandexVerification) {
    }

    public Seo build(String baseUrl) {
        return new Seo(baseUrl, jsonLd(companyProfileService.get(), baseUrl),
                blankToNull(googleVerification), blankToNull(yandexVerification));
    }

    String jsonLd(CompanyProfile c, String baseUrl) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("@context", "https://schema.org");
        m.put("@type", "Bakery");
        m.put("@id", baseUrl + "/#company");
        m.put("name", blankToNull(c.getName()) == null ? "Юг Сладости" : c.getName().strip());
        m.put("description", "Кондитерское производство в Шымкенте: коржики, пряники, кексы, булочки, "
                + "чак-чак и печенье оптом. Онлайн-заказ на сайте.");
        m.put("url", baseUrl + "/");
        m.put("logo", baseUrl + "/promo/img/logo.svg");
        m.put("image", baseUrl + "/promo/img/og-glavnaya.png");
        List<String> phones = c.getPhone() == null ? List.of() : Arrays.stream(c.getPhone().split("[,;]"))
                .map(String::strip).filter(s -> !s.isEmpty()).toList();
        if (!phones.isEmpty()) m.put("telephone", phones.get(0));
        if (blankToNull(c.getAddress()) != null) {
            Map<String, Object> address = new LinkedHashMap<>();
            address.put("@type", "PostalAddress");
            address.put("streetAddress", c.getAddress().strip());
            address.put("addressCountry", "KZ");
            m.put("address", address);
        }
        double[] point = point(c.getMapPoint());
        if (point != null) {
            m.put("geo", Map.of("@type", "GeoCoordinates", "latitude", point[0], "longitude", point[1]));
        }
        String hours = openingHours(c.getWorkHours());
        if (hours != null) m.put("openingHours", hours);
        m.put("areaServed", "Шымкент");
        m.put("servesCuisine", "Выпечка");
        List<String> sameAs = new ArrayList<>();
        if (blankToNull(c.getInstagramUrl()) != null) sameAs.add(c.getInstagramUrl().strip());
        if (blankToNull(c.getTiktokUrl()) != null) sameAs.add(c.getTiktokUrl().strip());
        if (!sameAs.isEmpty()) m.put("sameAs", sameAs);
        try {
            // "</" внутри <script> закрыл бы тег раньше времени
            return JSON.writeValueAsString(m).replace("</", "<\\/");
        } catch (JacksonException e) {
            return null;
        }
    }

    /** «Пн–Сб с 07:00 до 18:00, обед …» → «Mo-Sa 07:00-18:00»; не разобрали — null. */
    static String openingHours(String workHours) {
        if (workHours == null) return null;
        Matcher m = HOURS.matcher(workHours);
        if (!m.find()) return null;
        return DAYS_EN.get(DAYS_RU.indexOf(m.group(1))) + "-" + DAYS_EN.get(DAYS_RU.indexOf(m.group(2)))
                + " " + pad(m.group(3)) + "-" + pad(m.group(4));
    }

    private static String pad(String time) {
        return time.length() == 4 ? "0" + time : time;
    }

    private static double[] point(String mapPoint) {
        if (mapPoint == null) return null;
        String[] p = mapPoint.strip().split("[,;\\s]+");
        if (p.length != 2) return null;
        try {
            return new double[]{Double.parseDouble(p[0]), Double.parseDouble(p[1])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
