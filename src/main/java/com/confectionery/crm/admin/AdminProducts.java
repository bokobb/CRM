package com.confectionery.crm.admin;

import com.confectionery.crm.api.dto.ProductionCostDto;
import com.confectionery.crm.domain.*;
import com.confectionery.crm.service.OneCDepartments;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Collator;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Товары по макету (часть 3): каталог — отборы (на витрине, скрытые, без объёма коробки, полуфабрикаты, архив),
 * группы производства, порядок, чего не хватает в карточке; карточка товара — склад, заказы в работе, выпуск и
 * себестоимость по заявкам, витрина, рецептура и техкарта, 1С; форма — проверки как в макете.
 */
public final class AdminProducts {

    private AdminProducts() {
    }

    static final AdminFormat F = new AdminFormat();
    static final String NB = AdminFormat.NB;
    public static final String NO_GROUP = "Без группы";
    static final Collator RU = Collator.getInstance(Locale.forLanguageTag("ru"));
    private static final Pattern BOX_IN_NAME = Pattern.compile("\\((\\d+)(?:\\s*/\\s*\\d+)?\\s*шт\\.?\\)");

    static boolean pos(BigDecimal v) {
        return v != null && v.signum() > 0;
    }

    public static String group(Product p) {
        return p.getGroup() == null || p.getGroup().isBlank() ? NO_GROUP : p.getGroup().trim();
    }

    /** «(12 шт)» в конце названия — штук в коробке, как в приложении (Product.packingUnits); null — нет. */
    public static Integer boxOfName(String name) {
        Matcher m = BOX_IN_NAME.matcher(name == null ? "" : name);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    public static boolean hasRecipe(Recipe r) {
        return r != null && r.getItems() != null && !r.getItems().isEmpty();
    }

    public static boolean hasPhoto(Product p) {
        return p.getImageUrl() != null && !p.getImageUrl().isBlank() || p.getPhotos() != null && !p.getPhotos().isEmpty();
    }

    /** Скрыт от покупателей сегодня (по дату включительно; со следующего дня виден сам). */
    public static boolean hiddenNow(Product p) {
        return p.isActive() && !p.isVisibleToClients();
    }

    /** Чего не хватает в карточке — колонка «Замечания». */
    public static List<String> issues(Product p, Recipe r) {
        List<String> w = new ArrayList<>();
        if (p.isSemiFinished()) {
            if (!hasRecipe(r)) w.add("нет рецептуры");
            return w;
        }
        if (!p.isActive()) return w;
        if (!pos(p.getBoxVolumeM3())) w.add("нет объёма коробки");
        if (!pos(p.getUnitWeightKg())) w.add("нет веса");
        if (!hasPhoto(p)) w.add("нет фото");
        if (!hasRecipe(r)) w.add("нет рецептуры");
        if (p.getOneCNomenclatureKey() == null || p.getOneCNomenclatureKey().isBlank()) w.add("не связан с 1С");
        if (p.getOneCDepartmentKey() == null || p.getOneCDepartmentKey().isBlank()) w.add("не задан корпус");
        return w;
    }

    /** Плашка «Сайт»: на витрине / скрыт до … / в архиве / полуфабрикат не продаётся. */
    public record Vt(String cls, String icon, String text) {
    }

    public static Vt site(Product p) {
        if (p.isSemiFinished()) return new Vt("vt--pf", null, "не продаётся");
        if (!p.isActive()) return new Vt("vt--arch", null, "в архиве");
        if (hiddenNow(p)) {
            return new Vt("vt--hid", "eye-off", p.getHiddenFromClientsUntil() != null ? "скрыт до " + F.dm(p.getHiddenFromClientsUntil()) : "скрыт");
        }
        return new Vt("vt--on", "eye", "на витрине");
    }

    public static String department(Product p) {
        return p.getOneCDepartmentKey() == null ? null : OneCDepartments.NAMES.getOrDefault(p.getOneCDepartmentKey(), "подразделение 1С " + p.getOneCDepartmentKey());
    }

    // ================================================================== каталог

    public record Row(Product p, AdminStock.Row st, List<String> issues, Vt site) {
        public boolean pf() {
            return p.isSemiFinished();
        }

        public String photo() {
            return AdminOrders.photo(p);
        }

        public String sub() {
            String code = p.getOneCCode() != null && !p.getOneCCode().isBlank() ? "код " + p.getOneCCode() : "без кода";
            return code + (pf() ? " · полуфабрикат" : p.getCategory() != null && !p.getCategory().isBlank() ? " · " + p.getCategory().trim() : "");
        }

        public BigDecimal box() {
            return AdminOrders.boxUnits(p);
        }

        public boolean noVol() {
            return !pos(p.getBoxVolumeM3());
        }

        /** «0,026 м³ · 0,55 кг/шт» или «нет объёма коробки · 0,55 кг/шт». */
        public String pack() {
            String v = noVol() ? "нет объёма коробки" : F.dec(p.getBoxVolumeM3(), 3) + NB + "м³";
            return v + (pos(p.getUnitWeightKg()) ? " · " + F.dec(p.getUnitWeightKg(), 2) + NB + "кг/" + unit(p) : "");
        }

        public boolean shortage() {
            return st != null && st.state().equals("short");
        }

        public String issueText() {
            return String.join(" · ", issues);
        }

        public String issueTitle() {
            return String.join("\n", issues);
        }
    }

    public static String unit(Product p) {
        return p.getUnit() == null || p.getUnit().isBlank() ? "шт" : p.getUnit().trim();
    }

    public static final List<String[]> SEGS = List.of(new String[]{"all", "Все"}, new String[]{"site", "На витрине"},
            new String[]{"hidden", "Скрытые"}, new String[]{"nobox", "Без объёма коробки"}, new String[]{"pf", "Полуфабрикаты"},
            new String[]{"arch", "Архив"});

    public static final List<String[]> SORTS = List.of(new String[]{"grp", "По группам производства"}, new String[]{"name", "По названию"},
            new String[]{"price", "Цена: дороже сверху"}, new String[]{"stock", "Остаток: больше сверху"});

    public static boolean inSeg(Product p, String seg) {
        return switch (seg) {
            case "site" -> !p.isSemiFinished() && p.isActive() && !hiddenNow(p);
            case "hidden" -> hiddenNow(p) && !p.isSemiFinished();
            case "nobox" -> !p.isSemiFinished() && p.isActive() && !pos(p.getBoxVolumeM3());
            case "pf" -> p.isSemiFinished() && p.isActive();
            case "arch" -> !p.isActive();
            default -> p.isActive();
        };
    }

    public static boolean matches(Product p, String[] words) {
        if (words.length == 0) return true;
        String hay = AdminRequests.norm(p.getName() + " " + (p.getOneCCode() == null ? "" : p.getOneCCode()));
        for (String w : words) if (!hay.contains(w)) return false;
        return true;
    }

    /** Порядок групп: по алфавиту, «Без группы» — в конце. */
    public static Comparator<String> groupOrder() {
        return (a, b) -> a.equals(b) ? 0 : a.equals(NO_GROUP) ? 1 : b.equals(NO_GROUP) ? -1 : RU.compare(a, b);
    }

    public static Comparator<Row> sort(String sort) {
        Comparator<Row> byName = (a, b) -> RU.compare(a.p().getName(), b.p().getName());
        return switch (sort) {
            case "name" -> byName;
            case "price" -> Comparator.comparing((Row r) -> r.pf() || r.p().getPrice() == null ? BigDecimal.valueOf(-1) : r.p().getPrice()).reversed().thenComparing(byName);
            case "stock" -> Comparator.comparing((Row r) -> r.st() == null ? BigDecimal.valueOf(Long.MIN_VALUE) : r.st().stock()).reversed().thenComparing(byName);
            default -> Comparator.comparing((Row r) -> group(r.p()), groupOrder()).thenComparing(r -> r.pf() ? 1 : 0).thenComparing(byName);
        };
    }

    /** Группа в списке «по группам производства»: заголовок и товары; при другом порядке — одна группа без заголовка. */
    public record Grp(String title, List<Row> rows) {
        public String countText() {
            return F.count(rows.size(), "товар", "товара", "товаров");
        }
    }

    public static List<Grp> grouped(List<Row> rows, boolean byGroup) {
        if (!byGroup) return rows.isEmpty() ? List.of() : List.of(new Grp(null, rows));
        LinkedHashMap<String, List<Row>> m = new LinkedHashMap<>();
        for (Row r : rows) m.computeIfAbsent(group(r.p()), k -> new ArrayList<>()).add(r);
        List<Grp> out = new ArrayList<>();
        m.forEach((k, v) -> out.add(new Grp(k, v)));
        return out;
    }

    // ================================================================== карточка

    /** Плитка карточки; sub — HTML (числа и даты, без текста пользователей). */
    public record Kpi(String label, String value, String unit, String sub, String cls) {
    }

    /** Строка «Заказы в работе»: заказ, сколько этого товара, день выдачи. */
    public record OrderRow(Order o, BigDecimal q) {
        public String when() {
            LocalDate d = AdminOrders.day(o);
            return F.cap(F.relDay(d)) + (o.getPickupHour() != null ? ", " + F.win(o.getPickupHour()) : "");
        }

        public String client() {
            return o.getClient() == null ? "" : o.getClient().getName();
        }
    }

    public static List<OrderRow> orders(List<Order> open, Long productId) {
        List<OrderRow> out = new ArrayList<>();
        for (Order o : open) {
            if (!AdminStock.OPEN.contains(o.getStatus())) continue;
            BigDecimal q = BigDecimal.ZERO;
            boolean has = false;
            for (OrderItem i : o.getItems()) {
                if (i.getProduct() != null && productId.equals(i.getProduct().getId())) {
                    q = q.add(AdminStock.nz(i.getQuantity()));
                    has = true;
                }
            }
            if (has) out.add(new OrderRow(o, q));
        }
        out.sort(Comparator.comparing((OrderRow r) -> AdminOrders.day(r.o()))
                .thenComparing(r -> r.o().getPickupHour() == null ? 99 : r.o().getPickupHour()).thenComparing(r -> r.o().getId()));
        return out;
    }

    /** Строка заявки с этим товаром и её себестоимость (c — null, если не считали). */
    public record Made(ProductionRequest r, ProductionRequestLine l, ProductionCostDto.Line c) {
        public boolean extra() {
            return AdminRequests.extra(l);
        }

        public String status() {
            AdminRequests.Badge b = AdminRequests.badge(r);
            return (b != null ? b.label() : r.getStatus().getLabel()).toLowerCase(Locale.ROOT);
        }

        public BigDecimal unitCost() {
            return c == null ? null : c.unitCost();
        }

        public BigDecimal markup() {
            return c == null ? null : AdminProduction.markup(c.salePrice(), c.unitCost());
        }
    }

    /** Строки заявок (без отменённых) с товаром — новые сверху. */
    public static List<Made> made(List<ProductionRequest> requests, Long productId) {
        List<Made> out = new ArrayList<>();
        for (ProductionRequest r : requests) {
            if (r.getStatus() == ProductionRequestStatus.CANCELLED) continue;
            for (ProductionRequestLine l : r.getLines()) {
                if (l.getProduct() != null && productId.equals(l.getProduct().getId())) out.add(new Made(r, l, null));
            }
        }
        out.sort(Comparator.comparing((Made m) -> m.r().getDocumentDate()).reversed().thenComparing(m -> m.r().getId(), Comparator.reverseOrder()));
        return out;
    }

    /** Себестоимость 1 шт для плитки: последний оприходованный выпуск, нет его — последний по плану. */
    public static Made lastCost(List<Made> made) {
        for (Made m : made) if (m.l().isReceived() && m.unitCost() != null) return m;
        for (Made m : made) if (m.unitCost() != null) return m;
        return null;
    }

    /** Закладка замеса, кг (кг и л как есть, г и мл ÷ 1000; штучное сырьё не считается). */
    public static BigDecimal batchKg(Recipe r) {
        if (!hasRecipe(r)) return null;
        BigDecimal s = BigDecimal.ZERO;
        for (RecipeItem it : r.getItems()) {
            BigDecimal kg = AdminTechCards.kg(it.getQuantity(), it.getIngredient() == null ? null : it.getIngredient().getUnit());
            if (kg != null) s = s.add(kg);
        }
        return s.signum() > 0 ? s : null;
    }

    /** Тесто на одну штуку, г. */
    public static BigDecimal perUnitGrams(Recipe r) {
        BigDecimal kg = batchKg(r);
        if (kg == null || r.getYieldQuantity() == null || r.getYieldQuantity().signum() <= 0) return null;
        return kg.multiply(BigDecimal.valueOf(1000)).divide(r.getYieldQuantity(), 0, RoundingMode.HALF_UP);
    }

    // ================================================================== форма

    /** Введённые значения формы (строки как ввели) — ошибки не стирают ввод. */
    public record Form(String name, String category, String group, String groupNew, String price, String unit, String description,
                       boolean semi, String weight, String box, String vol, String box2, String vol2, String netG, boolean mref,
                       String dep) {
        static String s(BigDecimal v) {
            return v == null ? "" : v.stripTrailingZeros().toPlainString().replace('.', ',');
        }

        public static Form of(Product p) {
            return new Form(p.getName() == null ? "" : p.getName(), p.getCategory() == null ? "" : p.getCategory(),
                    p.getGroup() == null ? "" : p.getGroup(), "", p.isSemiFinished() || p.getId() == null && !pos(p.getPrice()) ? "" : s(p.getPrice()),
                    AdminProducts.unit(p), p.getDescription() == null ? "" : p.getDescription(), p.isSemiFinished(),
                    s(p.getUnitWeightKg()), s(p.getUnitsPerBox()), s(p.getBoxVolumeM3()), s(p.getUnitsPerBox2()), s(p.getBoxVolume2M3()),
                    s(p.getNetWeightGrams()), p.isMassReferenceProduct(), p.getOneCDepartmentKey() == null ? "" : p.getOneCDepartmentKey());
        }

        public static Form of(Map<String, String> f, boolean semi) {
            return new Form(t(f.get("name")), t(f.get("category")), t(f.get("group")), t(f.get("groupNew")), t(f.get("price")),
                    t(f.get("unit")).isEmpty() ? "шт" : t(f.get("unit")), f.getOrDefault("description", "").trim(), semi,
                    t(f.get("unitWeightKg")), t(f.get("unitsPerBox")), t(f.get("boxVolumeM3")), t(f.get("unitsPerBox2")),
                    t(f.get("boxVolume2M3")), t(f.get("netWeightGrams")), "true".equals(f.get("massReferenceProduct")), t(f.get("oneCDepartmentKey")));
        }

        private static String t(String s) {
            return s == null ? "" : s.trim();
        }

        /** Группа производства, которую сохраним: выбранная или новая. */
        public String groupValue() {
            return "__new".equals(group) ? groupNew : group;
        }
    }

    /** Число из поля: «0,026», «1 250» → BigDecimal; пусто → null; не число → NaN-маркер (-1). */
    public static BigDecimal num(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return new BigDecimal(s.replace(" ", "").replace(NB, "").replace(',', '.'));
        } catch (NumberFormatException e) {
            return BigDecimal.valueOf(-1);
        }
    }

    static boolean whole(BigDecimal v) {
        return v.signum() > 0 && v.stripTrailingZeros().scale() <= 0;
    }

    /** Проверки как в макете: поле → текст ошибки (порядок — как на экране). */
    public static LinkedHashMap<String, String> errors(Form f, Long selfId, String currentDep, List<Product> all) {
        LinkedHashMap<String, String> e = new LinkedHashMap<>();
        String nm = f.name().trim().toLowerCase(Locale.ROOT);
        if (nm.isEmpty()) e.put("name", "Введите название.");
        else if (nm.length() > 200) e.put("name", "Название — до 200 знаков.");
        else if (all.stream().anyMatch(p -> !Objects.equals(p.getId(), selfId) && p.getName() != null && p.getName().trim().toLowerCase(Locale.ROOT).equals(nm))) {
            e.put("name", "Такой товар уже есть.");
        }
        if (f.groupValue() == null || f.groupValue().isBlank()) {
            e.put("group", "__new".equals(f.group()) ? "Введите название новой группы производства." : "Выберите группу производства.");
        }
        if (!f.semi()) {
            BigDecimal price = num(f.price()), g = num(f.weight()), box = num(f.box()), vol = num(f.vol()), box2 = num(f.box2()), vol2 = num(f.vol2());
            if (price == null || price.signum() <= 0) e.put("price", "Цена больше нуля.");
            if (g != null && !(g.signum() > 0 && g.compareTo(BigDecimal.valueOf(50)) <= 0)) e.put("weight", "Вес — от 0,001 до 50 кг.");
            if (box != null && !whole(box)) e.put("box", "Целое число штук.");
            if (box == null && boxOfName(f.name()) == null) e.put("box", "В названии нет «(N шт)» — укажите, сколько штук в коробке.");
            if (vol != null && !(vol.signum() > 0 && vol.compareTo(BigDecimal.valueOf(2)) <= 0)) e.put("vol", "Объём — от 0,001 до 2 м³.");
            if (box2 != null && !whole(box2)) e.put("box2", "Целое число штук.");
            if (vol2 != null && !(vol2.signum() > 0 && vol2.compareTo(BigDecimal.valueOf(2)) <= 0)) e.put("vol2", "Объём — от 0,001 до 2 м³.");
            if ((box2 != null) != (vol2 != null) && !e.containsKey("box2") && !e.containsKey("vol2")) {
                e.put(box2 != null ? "vol2" : "box2", "Для второй коробки нужны и штуки, и объём.");
            }
        }
        if (f.description().length() > 1000) e.put("desc", "Описание — до 1000 знаков.");
        if (f.category().length() > 100 || f.groupValue() != null && f.groupValue().length() > 100) e.put("grpLen", "Название группы — до 100 знаков.");
        BigDecimal netG = num(f.netG());
        if (netG != null && !(netG.signum() > 0 && netG.compareTo(BigDecimal.valueOf(20000)) <= 0)) e.put("netG", "Вес — от 1 до 20 000 г.");
        if (f.mref() && netG == null && !e.containsKey("netG")) e.put("netG", "Эталону нужен вес нетто.");
        if (!f.dep().isEmpty() && !OneCDepartments.NAMES.containsKey(f.dep()) && !f.dep().equals(currentDep)) e.put("dep", "Выберите подразделение из списка.");
        return e;
    }
}
