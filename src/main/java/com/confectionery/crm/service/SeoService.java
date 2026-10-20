package com.confectionery.crm.service;

import com.confectionery.crm.domain.CompanyProfile;
import com.confectionery.crm.domain.Vacancy;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Для поисковиков и ИИ-ответов (Google, Gemini, Яндекс): заголовок, описание, превью и разметка
 * schema.org каждой страницы сайта — из «Профиля компании» и каталога, ничего не хардкодим (17.10,
 * 20.10 — @graph: компания + сайт + страница + «хлебные крошки», на главной ещё и ассортимент).
 * Коды подтверждения Search Console / Яндекс Вебмастера / Bing — из backend.env (пусто — мета-тега нет).
 */
@Service
@RequiredArgsConstructor
public class SeoService {

    /** Публичные страницы сайта для sitemap.xml: путь → как часто меняется. */
    public static final Map<String, String> PAGES = Map.of(
            "/", "daily", "/shop", "weekly", "/contacts", "monthly", "/vacancies", "weekly");

    public static final String DEFAULT_NAME = "Юг Сладости";

    /** Город в заголовках: производство под Шымкентом, ищут его как «кондитерская Шымкент». */
    private static final String CITY = "Шымкент";

    private static final Pattern HOURS = Pattern.compile("(Пн|Вт|Ср|Чт|Пт|Сб|Вс)\\s*[–—-]\\s*(Пн|Вт|Ср|Чт|Пт|Сб|Вс)"
            + "\\D*?(\\d{1,2}:\\d{2})\\D+?(\\d{1,2}:\\d{2})");
    private static final List<String> DAYS_RU = List.of("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс");
    private static final List<String> DAYS_EN = List.of("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su");
    private static final List<String> DAYS_SCHEMA = List.of("Monday", "Tuesday", "Wednesday", "Thursday",
            "Friday", "Saturday", "Sunday");
    private static final Pattern LOCALITY = Pattern.compile("^(г\\.|город|с\\.|село|пос\\.|п\\.|аул)\\s*(.+)$");

    private final CompanyProfileService companyProfileService;
    private static final ObjectMapper JSON = new ObjectMapper();

    @Value("${crm.seo.google-verification:}")
    private String googleVerification;

    @Value("${crm.seo.yandex-verification:}")
    private String yandexVerification;

    @Value("${crm.seo.bing-verification:}")
    private String bingVerification;

    public enum Kind { HOME, CONTACTS, VACANCIES, SHOP, PRIVATE }

    /**
     * То, что кладём в модель страниц сайта под именем "seo". noindex — внутренние страницы магазина
     * (корзина, история): поисковикам там нечего показывать.
     */
    public record Seo(String baseUrl, String canonical, String title, String description, String ogImage,
                      String ogImageAlt, boolean noindex, String jsonLd,
                      String googleVerification, String yandexVerification, String bingVerification) {
    }

    /** Адрес из профиля по частям для разметки: «Туркестанская область, …, с. Низамабад, ул. Низамабад, 163». */
    record Address(String region, String locality, String localityFull, String street) {
        /** «с. Низамабад, ул. Низамабад, 163» — для описаний. */
        String shortText() {
            return localityFull == null ? street : street == null ? localityFull : localityFull + ", " + street;
        }
    }

    public Seo home(String baseUrl, PromoPageService.Page page) {
        return build(Kind.HOME, baseUrl, page, List.of());
    }

    public Seo page(Kind kind, String baseUrl, List<Vacancy> vacancies) {
        return build(kind, baseUrl, null, vacancies);
    }

    private Seo build(Kind kind, String baseUrl, PromoPageService.Page page, List<Vacancy> vacancies) {
        CompanyProfile c = companyProfileService.get();
        String name = name(c);
        String path = switch (kind) {
            case HOME -> "/";
            case CONTACTS -> "/contacts";
            case VACANCIES -> "/vacancies";
            case SHOP, PRIVATE -> "/shop";
        };
        String title = switch (kind) {
            case HOME -> name + " — кондитерская в " + CITY + "е, выпечка к чаю оптом";
            case CONTACTS -> "Контакты — «" + name + "», кондитерская в " + CITY + "е";
            case VACANCIES -> "Вакансии — «" + name + "», " + CITY;
            case SHOP, PRIVATE -> "Онлайн-заказ — " + name;
        };
        String description = switch (kind) {
            case HOME -> homeDescription(c, page);
            case CONTACTS -> contactsDescription(c);
            case VACANCIES -> vacanciesDescription(c, vacancies);
            case SHOP, PRIVATE -> "Оптовый онлайн-заказ выпечки «" + name + "»: вход по номеру телефона, ваши цены, "
                    + "корзина и история заказов." + phoneSentence(c, " Помощь: ");
        };
        String ogImage = baseUrl + switch (kind) {
            case CONTACTS -> "/promo/img/og-kontakty.png";
            case VACANCIES -> "/promo/img/og-vakansii.png";
            default -> "/promo/img/og-glavnaya.png";
        };
        String canonical = baseUrl + path;
        String jsonLd = kind == Kind.PRIVATE ? null : jsonLd(kind, c, baseUrl, canonical, title, description, ogImage, page);
        return new Seo(baseUrl, canonical, title, description, ogImage, name + " — кондитерская в " + CITY + "е",
                kind == Kind.PRIVATE, jsonLd, blankToNull(googleVerification), blankToNull(yandexVerification),
                blankToNull(bingVerification));
    }

    private static String homeDescription(CompanyProfile c, PromoPageService.Page page) {
        StringBuilder s = new StringBuilder("«").append(name(c)).append("» — кондитерская в ").append(CITY).append("е");
        if (c.getFoundedYear() != null) s.append(" с ").append(c.getFoundedYear()).append(" года");
        String groups = page == null ? "" : topGroups(page, 4);
        s.append(groups.isEmpty() ? ": выпечка к чаю оптом" : ": " + groups + " оптом").append('.');
        if (page != null && page.productCount() > 0) s.append(' ').append(page.positions(page.productCount())).append(',');
        s.append(" онлайн-заказ на сайте.");
        return s.toString();
    }

    private static String contactsDescription(CompanyProfile c) {
        StringBuilder s = new StringBuilder("Кондитерская «").append(name(c)).append("»");
        s.append(phoneSentence(c, ": телефон "));
        Address a = address(c.getAddress());
        if (a != null) s.append(s.indexOf(":") > 0 ? ", " : ": ").append("производство — ").append(a.shortText());
        if (blankToNull(c.getWorkHours()) != null) {
            // без «обеда» — описание в выдаче обрезается после ~160 знаков
            s.append(". ").append(c.getWorkHours().strip().replaceFirst("\\s*[,;]\\s*обед.*$", ""));
        }
        return s.append(". Маршрут в 2ГИС и Яндекс Картах.").toString();
    }

    private static String vacanciesDescription(CompanyProfile c, List<Vacancy> vacancies) {
        String titles = vacancies.stream().limit(4).map(v -> v.getTitle().strip().toLowerCase(new Locale("ru")))
                .collect(Collectors.joining(", "));
        return "Работа на кондитерском производстве «" + name(c) + "», " + CITY
                + (titles.isEmpty() ? "" : ": " + titles) + ". График, зарплата и отклик прямо на сайте.";
    }

    /** «коржики, кексы, булочки и пряники» — самые большие группы каталога. */
    private static String topGroups(PromoPageService.Page page, int n) {
        List<String> names = page.groups().stream()
                .filter(g -> !"Другое".equals(g.name()))
                .sorted(Comparator.comparingInt(PromoPageService.Group::count).reversed())
                .limit(n)
                .map(g -> g.name().toLowerCase(new Locale("ru")))
                .toList();
        if (names.size() <= 1) return String.join("", names);
        return String.join(", ", names.subList(0, names.size() - 1)) + " и " + names.get(names.size() - 1);
    }

    private static String phoneSentence(CompanyProfile c, String prefix) {
        List<String> phones = phones(c);
        return phones.isEmpty() ? "" : prefix + String.join(", ", phones);
    }

    String jsonLd(Kind kind, CompanyProfile c, String baseUrl, String canonical, String title, String description,
                  String ogImage, PromoPageService.Page page) {
        List<Object> graph = new ArrayList<>();
        graph.add(company(c, baseUrl, ogImage, page, kind == Kind.HOME));
        graph.add(website(c, baseUrl));

        Map<String, Object> webPage = node(kind == Kind.CONTACTS ? "ContactPage" : "WebPage", canonical + "#webpage");
        webPage.put("url", canonical);
        webPage.put("name", title);
        webPage.put("description", description);
        webPage.put("inLanguage", "ru");
        webPage.put("isPartOf", ref(baseUrl + "/#website"));
        webPage.put("about", ref(baseUrl + "/#company"));
        webPage.put("primaryImageOfPage", Map.of("@type", "ImageObject", "url", ogImage, "width", 1200, "height", 630));
        graph.add(webPage);
        if (kind != Kind.HOME) {
            webPage.put("breadcrumb", ref(canonical + "#breadcrumb"));
            String crumb = switch (kind) {
                case CONTACTS -> "Контакты";
                case VACANCIES -> "Вакансии";
                default -> "Онлайн-заказ";
            };
            Map<String, Object> breadcrumbs = node("BreadcrumbList", canonical + "#breadcrumb");
            breadcrumbs.put("itemListElement", List.of(
                    Map.of("@type", "ListItem", "position", 1, "name", "Главная", "item", baseUrl + "/"),
                    Map.of("@type", "ListItem", "position", 2, "name", crumb, "item", canonical)));
            graph.add(breadcrumbs);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("@context", "https://schema.org");
        root.put("@graph", graph);
        try {
            // "</" внутри <script> закрыл бы тег раньше времени
            return JSON.writeValueAsString(root).replace("</", "<\\/");
        } catch (JacksonException e) {
            return null;
        }
    }

    /** Компания — «Кондитерская» (schema.org Bakery): всё, что поисковик и ИИ-ответ могут о ней сказать. */
    private Map<String, Object> company(CompanyProfile c, String baseUrl, String ogImage, PromoPageService.Page page,
                                        boolean withMenu) {
        Map<String, Object> m = node("Bakery", baseUrl + "/#company");
        String name = name(c);
        m.put("name", name);
        m.put("alternateName", List.of("Yug Sladosti", "ЮС"));
        if (c.legalName() != null) m.put("legalName", c.legalName());
        if (c.taxId() != null) m.put("taxID", c.taxId());
        if (c.getFoundedYear() != null) m.put("foundingDate", String.valueOf(c.getFoundedYear()));
        m.put("description", companyDescription(c, page));
        m.put("slogan", "Выпечка к чаю оптом — прямо с производства");
        m.put("url", baseUrl + "/");
        m.put("logo", Map.of("@type", "ImageObject", "@id", baseUrl + "/#logo", "url", baseUrl + "/promo/img/logo-1200.png",
                "contentUrl", baseUrl + "/promo/img/logo-1200.png", "width", 1200, "height", 692, "caption", name));
        m.put("image", List.of(ogImage, baseUrl + "/promo/img/logo-1200.png"));

        List<String> phones = phones(c);
        if (!phones.isEmpty()) {
            m.put("telephone", phones.get(0));
            List<Map<String, Object>> points = new ArrayList<>();
            for (String phone : phones) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("@type", "ContactPoint");
                p.put("telephone", phone);
                p.put("contactType", "sales");
                p.put("areaServed", "KZ");
                p.put("availableLanguage", "ru");
                points.add(p);
            }
            String jobs = blankToNull(c.getJobsPhone());
            if (jobs != null) {
                points.add(Map.of("@type", "ContactPoint", "telephone", jobs, "contactType", "Отдел кадров (вакансии)"));
            }
            m.put("contactPoint", points);
        }
        if (c.siteEmail() != null) m.put("email", c.siteEmail());

        Address a = address(c.getAddress());
        if (a != null) {
            Map<String, Object> address = new LinkedHashMap<>();
            address.put("@type", "PostalAddress");
            if (a.street() != null) address.put("streetAddress", a.street());
            if (a.locality() != null) address.put("addressLocality", a.locality());
            if (a.region() != null) address.put("addressRegion", a.region());
            address.put("addressCountry", "KZ");
            m.put("address", address);
        }
        double[] point = point(c.getMapPoint());
        if (point != null) {
            m.put("geo", Map.of("@type", "GeoCoordinates", "latitude", point[0], "longitude", point[1]));
            m.put("hasMap", "https://yandex.kz/maps/?pt=" + point[1] + "," + point[0] + "&z=17&l=map");
        }
        String hours = openingHours(c.getWorkHours());
        if (hours != null) {
            m.put("openingHours", hours);
            m.put("openingHoursSpecification", openingHoursSpecification(c.getWorkHours()));
        }
        m.put("areaServed", List.of(Map.of("@type", "City", "name", CITY), Map.of("@type", "Country", "name", "Казахстан")));
        m.put("currenciesAccepted", "KZT");
        m.put("servesCuisine", "Выпечка");
        if (page != null && !page.groups().isEmpty()) {
            m.put("knowsAbout", page.groups().stream().map(PromoPageService.Group::name)
                    .filter(g -> !"Другое".equals(g)).toList());
        }
        if (withMenu && page != null && !page.cards().isEmpty()) m.put("hasMenu", menu(baseUrl, page));

        List<String> sameAs = new ArrayList<>();
        if (blankToNull(c.getInstagramUrl()) != null) sameAs.add(c.getInstagramUrl().strip());
        if (blankToNull(c.getTiktokUrl()) != null) sameAs.add(c.getTiktokUrl().strip());
        sameAs.addAll(c.externalLinkList());
        if (!sameAs.isEmpty()) m.put("sameAs", sameAs.stream().distinct().toList());
        return m;
    }

    /** Ассортимент как «меню» кондитерской: группы → позиции с весом и количеством в коробке. */
    private static Map<String, Object> menu(String baseUrl, PromoPageService.Page page) {
        Map<String, Object> menu = node("Menu", baseUrl + "/#catalog");
        menu.put("name", "Каталог " + page.year());
        menu.put("url", baseUrl + "/#catalog");
        List<Map<String, Object>> sections = new ArrayList<>();
        for (PromoPageService.Group g : page.groups()) {
            List<Map<String, Object>> items = new ArrayList<>();
            for (PromoPageService.Card card : page.cards()) {
                if (!g.name().equals(card.group())) continue;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("@type", "MenuItem");
                item.put("name", card.name());
                if (card.meta() != null && !card.meta().isBlank()) item.put("description", card.meta());
                if (card.imageUrl() != null) item.put("image", absolute(baseUrl, card.imageUrl()));
                items.add(item);
            }
            Map<String, Object> section = new LinkedHashMap<>();
            section.put("@type", "MenuSection");
            section.put("name", g.name());
            section.put("hasMenuItem", items);
            sections.add(section);
        }
        menu.put("hasMenuSection", sections);
        return menu;
    }

    private static Map<String, Object> website(CompanyProfile c, String baseUrl) {
        Map<String, Object> w = node("WebSite", baseUrl + "/#website");
        w.put("url", baseUrl + "/");
        // Название сайта в выдаче Google («Юг Сладости» вместо адреса yug-sladosti.kz)
        w.put("name", name(c));
        w.put("alternateName", List.of("Yug Sladosti", baseUrl.replaceFirst("^https?://", "")));
        w.put("inLanguage", "ru");
        w.put("publisher", ref(baseUrl + "/#company"));
        return w;
    }

    /** Абзац о компании для разметки и llms.txt: что, где, с какого года, ассортимент, для кого. */
    public String companyDescription(CompanyProfile c, PromoPageService.Page page) {
        StringBuilder s = new StringBuilder("«").append(name(c)).append("» — кондитерская с собственным производством в ")
                .append(CITY).append("е (Казахстан)");
        if (c.getFoundedYear() != null) s.append(", работает с ").append(c.getFoundedYear()).append(" года");
        s.append(". Выпечка к чаю оптом для магазинов, кафе, столовых и дистрибьюторов");
        if (page != null && page.productCount() > 0) {
            s.append(": ").append(page.positions(page.productCount()));
            if (!page.groupList().isEmpty()) s.append(" — ").append(page.groupList());
        }
        s.append(". Онлайн-заказ на сайте.");
        String about = blankToNull(c.getAbout());
        if (about != null) s.append(' ').append(about.replaceAll("\\s+", " "));
        return s.toString();
    }

    /** «Пн–Сб с 07:00 до 18:00, обед …» → «Mo-Sa 07:00-18:00»; не разобрали — null. */
    static String openingHours(String workHours) {
        Matcher m = hours(workHours);
        if (m == null) return null;
        return DAYS_EN.get(DAYS_RU.indexOf(m.group(1))) + "-" + DAYS_EN.get(DAYS_RU.indexOf(m.group(2)))
                + " " + pad(m.group(3)) + "-" + pad(m.group(4));
    }

    private static Map<String, Object> openingHoursSpecification(String workHours) {
        Matcher m = hours(workHours);
        int from = DAYS_RU.indexOf(m.group(1)), to = DAYS_RU.indexOf(m.group(2));
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("@type", "OpeningHoursSpecification");
        spec.put("dayOfWeek", from <= to ? DAYS_SCHEMA.subList(from, to + 1) : DAYS_SCHEMA);
        spec.put("opens", pad(m.group(3)));
        spec.put("closes", pad(m.group(4)));
        return spec;
    }

    private static Matcher hours(String workHours) {
        if (workHours == null) return null;
        Matcher m = HOURS.matcher(workHours);
        return m.find() ? m : null;
    }

    /** Адрес профиля по частям; область — часть с «област», населённый пункт — «г.»/«с.»/…, дальше улица. */
    static Address address(String raw) {
        if (blankToNull(raw) == null) return null;
        String text = raw.strip();
        int cut = text.indexOf(" (");
        if (cut > 0) text = text.substring(0, cut);
        text = text.replaceAll("[.\\s]+$", "");
        List<String> parts = Arrays.stream(text.split("\\s*,\\s*")).filter(p -> !p.isEmpty()).toList();
        String region = parts.stream().filter(p -> p.toLowerCase(Locale.ROOT).contains("област")).findFirst().orElse(null);
        for (int i = 0; i < parts.size(); i++) {
            Matcher m = LOCALITY.matcher(parts.get(i));
            if (m.matches()) {
                String street = i + 1 < parts.size() ? String.join(", ", parts.subList(i + 1, parts.size())) : null;
                return new Address(region, m.group(2).strip(), parts.get(i), street);
            }
        }
        return new Address(region, null, null, text);
    }

    public static List<String> phones(CompanyProfile c) {
        return c.getPhone() == null ? List.of() : Arrays.stream(c.getPhone().split("[,;]"))
                .map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    public static String name(CompanyProfile c) {
        return blankToNull(c.getName()) == null ? DEFAULT_NAME : c.getName().strip();
    }

    public static String absolute(String baseUrl, String url) {
        return url.startsWith("/") ? baseUrl + url : url;
    }

    private static Map<String, Object> node(String type, String id) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("@type", type);
        m.put("@id", id);
        return m;
    }

    private static Map<String, Object> ref(String id) {
        return Map.of("@id", id);
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
