package com.confectionery.crm.service;

import com.confectionery.crm.domain.CompanyProfile;
import com.confectionery.crm.domain.Product;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.Year;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Данные рекламной страницы на "/" (templates/promo.html) — целиком из CRM, ничего не
 * захардкожено: товары — те же, что видят клиенты на /shop (без архива, полуфабрикатов и
 * скрытых, см. Product#isVisibleToClients), группы — поле «Категория» товара, вес и упаковка —
 * из карточки товара, контакты — из профиля компании.
 */
@Service
@RequiredArgsConstructor
public class PromoPageService {

    private static final String NO_GROUP = "Другое";

    /** «Коржики (12/24 шт)» → 12 и 24 — как Product.unitsFromName в приложении. */
    private static final Pattern UNITS_IN_NAME = Pattern.compile("\\((\\d+)(?:\\s*/\\s*(\\d+))?\\s*шт\\.?\\)");

    /**
     * Вырезанная выпечка без фона для круглой плитки группы в ленте под первым экраном. Группа —
     * просто текст «Категория» у товара, отдельной сущности нет, поэтому соответствие здесь.
     * Группы без выреза (новые) показывают фото товара, обрезанное по кругу.
     */
    private static final Map<String, String> GROUP_CUTOUTS = Map.of(
            "коржики", "/promo/img/c-korzhiki.webp",
            "пряники и печенье", "/promo/img/c-gr-pryaniki.webp",
            "кексы", "/promo/img/c-gr-keksy.webp",
            "булочки", "/promo/img/c-gr-bulochki.webp",
            "чак-чак", "/promo/img/c-chak-chak-v-chashke.webp",
            "орешки и рогалики", "/promo/img/c-kedrovye-oreshki.webp",
            "печенье в коробках", "/promo/img/c-pechenye-pesochnoe.webp",
            "торты и пирожные", "/promo/img/c-pirozhnoe-mini.webp");

    private final ProductService productService;
    private final CompanyProfileService companyProfileService;

    public Page build() {
        List<Card> cards = productService.findVisibleToClients().stream()
                .sorted(Comparator.comparing(Product::getName, String.CASE_INSENSITIVE_ORDER))
                .map(this::card)
                .toList();

        Map<String, List<Card>> byGroup = new LinkedHashMap<>();
        cards.stream()
                .map(Card::group)
                .distinct()
                .sorted(Comparator.comparing((String g) -> NO_GROUP.equals(g)).thenComparing(String.CASE_INSENSITIVE_ORDER))
                .forEach(g -> byGroup.put(g, new ArrayList<>()));
        cards.forEach(c -> byGroup.get(c.group()).add(c));

        List<Group> groups = new ArrayList<>();
        int i = 0;
        for (Map.Entry<String, List<Card>> e : byGroup.entrySet()) {
            String cutout = GROUP_CUTOUTS.get(e.getKey().toLowerCase(java.util.Locale.ROOT));
            String image = cutout != null ? cutout
                    : e.getValue().stream().map(Card::imageUrl).filter(u -> u != null).findFirst().orElse(null);
            groups.add(new Group("g" + i++, e.getKey(), e.getValue().size(), image, cutout != null));
        }
        Map<String, String> keyByName = new LinkedHashMap<>();
        groups.forEach(g -> keyByName.put(g.name(), g.key()));
        cards = cards.stream().map(c -> c.withGroupKey(keyByName.get(c.group()))).toList();

        BigDecimal minG = null;
        BigDecimal maxG = null;
        for (Card c : cards) {
            if (c.weightGrams() == null) continue;
            if (minG == null || c.weightGrams().compareTo(minG) < 0) minG = c.weightGrams();
            if (maxG == null || c.weightGrams().compareTo(maxG) > 0) maxG = c.weightGrams();
        }
        String weightRange = minG == null ? null
                : minG.compareTo(maxG) == 0 ? weight(minG)
                : weight(minG) + " – " + weight(maxG);

        return new Page(companyProfileService.get(), cards, groups, cards.size(), weightRange, Year.now().getValue());
    }

    /** Карточка товара для витрины — тот же расчёт веса и коробок использует магазин /shop (ShopController). */
    public Card card(Product p) {
        BigDecimal unitKg = positive(p.getUnitWeightKg());
        BigDecimal netG = positive(p.getNetWeightGrams());
        // Вес 1 шт на этикетке — нетто, если задан; иначе брутто из упаковки.
        BigDecimal weightG = netG != null ? netG : unitKg != null ? unitKg.multiply(BigDecimal.valueOf(1000)) : null;
        BigDecimal pieceKg = unitKg != null ? unitKg : netG != null ? netG.movePointLeft(3) : null;

        List<BigDecimal> fromName = unitsFromName(p.getName());
        BigDecimal units1 = positive(p.getUnitsPerBox()) != null ? p.getUnitsPerBox() : fromName.isEmpty() ? null : fromName.get(0);
        BigDecimal units2 = positive(p.getUnitsPerBox2()) != null ? p.getUnitsPerBox2() : fromName.size() > 1 ? fromName.get(1) : null;

        String perBox = null;
        String boxWeight = null;
        if (units1 != null) {
            perBox = number(units1) + (units2 != null ? "/" + number(units2) : "") + " шт";
            if (pieceKg != null) {
                boxWeight = number(units1.multiply(pieceKg).setScale(2, RoundingMode.HALF_UP))
                        + (units2 != null ? "/" + number(units2.multiply(pieceKg).setScale(2, RoundingMode.HALF_UP)) : "")
                        + " кг";
            }
        }
        String group = p.getCategory() == null || p.getCategory().isBlank() ? NO_GROUP : p.getCategory().trim();
        String title = UNITS_IN_NAME.matcher(p.getName()).replaceAll("").trim();
        String image = p.getImageUrl() == null || p.getImageUrl().isBlank() ? null : p.getImageUrl();
        List<Photo> photos = new ArrayList<>();
        p.getPhotos().forEach(ph -> photos.add(new Photo(ph.getUrl(), ph.getWidth(), ph.getHeight(),
                title + ", фото " + (photos.size() + 1))));
        if (photos.isEmpty() && image != null) {
            photos.add(new Photo(image, null, null, title));
        }
        String w = weightG == null ? null : weight(weightG);
        String meta = String.join(" · ", Stream.of(w, perBox == null ? null : "в коробке " + perBox)
                .filter(x -> x != null).toList());
        return new Card(title, group, null, image, weightG, w, perBox, boxWeight, units1, photos, meta);
    }

    private static List<BigDecimal> unitsFromName(String name) {
        Matcher m = UNITS_IN_NAME.matcher(name == null ? "" : name);
        if (!m.find()) return List.of();
        List<BigDecimal> out = new ArrayList<>();
        out.add(new BigDecimal(m.group(1)));
        if (m.group(2) != null) out.add(new BigDecimal(m.group(2)));
        return out;
    }

    private static BigDecimal positive(BigDecimal v) {
        return v != null && v.signum() > 0 ? v : null;
    }

    /** 550 → «550 г», 1750 → «1,75 кг». */
    private static String weight(BigDecimal grams) {
        return grams.compareTo(BigDecimal.valueOf(1000)) >= 0
                ? number(grams.movePointLeft(3)) + " кг"
                : number(grams.setScale(0, RoundingMode.HALF_UP)) + " г";
    }

    private static String number(BigDecimal v) {
        NumberFormat f = NumberFormat.getInstance(new Locale("ru"));
        f.setMaximumFractionDigits(2);
        f.setGroupingUsed(false);
        return f.format(v);
    }

    public record Page(CompanyProfile company, List<Card> cards, List<Group> groups, int productCount,
                       String weightRange, int year) {
        /** «43 позиции» / «1 позиция» / «2 позиции». */
        public String positions(int n) {
            int mod10 = n % 10, mod100 = n % 100;
            String word = mod10 == 1 && mod100 != 11 ? "позиция"
                    : mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14) ? "позиции" : "позиций";
            return n + " " + word;
        }

        /** «коржики, пряники и кексы» — для текста «о кондитерской». */
        public String groupList() {
            List<String> names = groups.stream().map(Group::name).filter(n -> !NO_GROUP.equals(n))
                    .map(n -> n.toLowerCase(new Locale("ru"))).toList();
            if (names.size() <= 1) return String.join("", names);
            return String.join(", ", names.subList(0, names.size() - 1)) + " и " + names.get(names.size() - 1);
        }
    }

    /** cutout — imageUrl это вырез без фона (иначе фото товара, плитка обрезает его по кругу). */
    public record Group(String key, String name, int count, String imageUrl, boolean cutout) {}

    /** boxUnits — штук в основной коробке (для «+ коробка» и подсчёта коробок в /shop), null — не задано. */
    /**
     * photos — альбом товара (ProductPhoto) по порядку, у товара без альбома — одно imageUrl;
     * meta — подпись под фото в альбоме («550 г · в коробке 12 шт»).
     */
    public record Card(String name, String group, String groupKey, String imageUrl,
                       BigDecimal weightGrams, String weight, String perBox, String boxWeight, BigDecimal boxUnits,
                       List<Photo> photos, String meta) {
        Card withGroupKey(String key) {
            return new Card(name, group, key, imageUrl, weightGrams, weight, perBox, boxWeight, boxUnits, photos, meta);
        }

        /** Для data-photos — альбом на сайте (static/promo/album.js) читает [{src,w,h,alt}]. */
        public String photosJson() {
            return JSON.writeValueAsString(photos);
        }
    }

    /** Фото в альбоме сайта; w/h — null, если размер неизвестен (альбом узнает при загрузке). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Photo(String src, Integer w, Integer h, String alt) {}

    private static final ObjectMapper JSON = new ObjectMapper();
}
