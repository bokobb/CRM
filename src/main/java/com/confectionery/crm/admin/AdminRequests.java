package com.confectionery.crm.admin;

import com.confectionery.crm.domain.*;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;

/**
 * Заявки на сырьё по макету (часть 2): статус для экрана («Новая» → «Собрано» → «Оприходовано» → «Завершена»,
 * «Отменена»), строки списка с тем, на что смотреть, и карточка — шаги, выпуск, сырьё (чек-лист сборки или
 * «норма · в заявке · выдано · разница»), 1С и история. Всё считается из ProductionRequest и его истории
 * (ProductionRequestRevision) — ничего нового в базе.
 */
public final class AdminRequests {

    private AdminRequests() {
    }

    private static final AdminFormat F = new AdminFormat();
    static final String NB = AdminFormat.NB;

    // ------------------------------------------------------------------ статус

    /** new · collected · received · done (оприходована и завершена) · off */
    public static String key(ProductionRequest r) {
        return switch (r.getStatus()) {
            case NEW -> "new";
            case COLLECTED -> "collected";
            case RECEIVED -> r.isRequisitionCompleted() ? "done" : "received";
            case CANCELLED -> "off";
        };
    }

    public record Badge(String label, String cls, String icon) {
    }

    private static final Map<String, Badge> BADGES = Map.of(
            "new", new Badge("Новая", "st--sent", "s-new"),
            "collected", new Badge("Собрано", "st--work", "materials"),
            "received", new Badge("Оприходовано", "st--ready", "stock"),
            "done", new Badge("Завершена", "st--done", "s-done"),
            "off", new Badge("Отменена", "st--off", "s-off"));

    public static Badge badge(String key) {
        return BADGES.get(key);
    }

    public static Badge badge(ProductionRequest r) {
        return BADGES.get(key(r));
    }

    /** Видны в списке всегда: ещё что-то нужно сделать. */
    public static boolean active(ProductionRequest r) {
        String k = key(r);
        return k.equals("new") || k.equals("collected") || k.equals("received");
    }

    public static final List<String> ORDER = List.of("new", "collected", "received", "done", "off");

    // ------------------------------------------------------------------ строки и числа

    /** Строка «сверх заявки» (ProductionRequestService.receiveExtra): 0 замесов, плана нет. */
    public static boolean extra(ProductionRequestLine l) {
        return l.getBatches() == null || l.getBatches().signum() == 0;
    }

    public static List<ProductionRequestLine> main(ProductionRequest r) {
        return r.getLines().stream().filter(l -> !extra(l)).toList();
    }

    public static BigDecimal batches(ProductionRequest r) {
        return main(r).stream().map(ProductionRequestLine::getBatches).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public static BigDecimal plan(ProductionRequest r) {
        return main(r).stream().map(l -> nz(l.getPlannedQuantity())).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public static BigDecimal fact(ProductionRequest r) {
        return r.getLines().stream().filter(ProductionRequestLine::isReceived).map(ProductionRequestLine::getReceivedQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Сколько основных позиций уже оприходовано. */
    public static int got(ProductionRequest r) {
        return (int) main(r).stream().filter(ProductionRequestLine::isReceived).count();
    }

    /** «Кекс с изюмом (15 шт)» → «Кекс с изюмом». */
    public static String shortName(String name) {
        return name == null ? "" : name.replaceAll("\\s*\\([^)]*\\)\\s*$", "");
    }

    /** Вода и лёд на складе не учитываются: их нет в остатках, нехватке и разнице «выдано — в заявке». */
    public static boolean untracked(String name) {
        return name != null && name.trim().toLowerCase(Locale.ROOT).matches("^(вода|лёд|лед)([\\s,(].*)?$");
    }

    public static boolean untracked(Ingredient i) {
        return untracked(i.getName());
    }

    static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    static String unit(Ingredient i) {
        return i.getUnit() == null || i.getUnit().isBlank() ? "кг" : i.getUnit();
    }

    /** Количество сырья как в макете: 1 180 · 41,4 · 0,138 — до 3 значащих знаков после запятой. */
    public static String qn(BigDecimal v) {
        if (v == null) return "—";
        BigDecimal a = v.abs();
        int d = a.compareTo(BigDecimal.valueOf(100)) >= 0 ? 1 : a.compareTo(BigDecimal.TEN) >= 0 ? 2 : 3;
        return F.num(v.setScale(d, RoundingMode.HALF_UP));
    }

    public static String qu(BigDecimal v, String unit) {
        return qn(v) + NB + (unit == null || unit.isBlank() ? "кг" : unit);
    }

    /** 3 замеса · 6,5 замеса · 1 замес */
    public static String zam(BigDecimal b) {
        if (b == null) return "";
        if (b.stripTrailingZeros().scale() > 0) return qn(b) + NB + "замеса";
        long n = b.longValue();
        return n + NB + F.plural(n, "замес", "замеса", "замесов");
    }

    // ------------------------------------------------------------------ сырьё: норма, выдано, разница

    /** Рецептура × замесы по всем изделиям заявки — как при создании (ProductionRequestService.populate). */
    public static Map<Ingredient, BigDecimal> norm(ProductionRequest r, Function<Long, Recipe> recipeOf) {
        Map<Ingredient, BigDecimal> out = new LinkedHashMap<>();
        for (ProductionRequestLine l : main(r)) {
            Recipe rc = recipeOf.apply(l.getProduct().getId());
            if (rc == null) continue;
            for (RecipeItem ri : rc.getItems()) {
                out.merge(ri.getIngredient(), nz(ri.getQuantity()).multiply(l.getBatches(), MathContext.DECIMAL64), BigDecimal::add);
            }
        }
        out.replaceAll((i, q) -> "шт".equalsIgnoreCase(unit(i).trim()) ? q.setScale(0, RoundingMode.CEILING) : q);
        return out;
    }

    public static ProductionRequestRevision lastIssued(ProductionRequest r) {
        ProductionRequestRevision last = null;
        for (ProductionRequestRevision x : r.getRevisions()) if (x.isIssued()) last = x;
        return last;
    }

    /** Что на руках у цеха: последний снимок «выдано»; null — сырьё не выдавали (или выдали до появления истории). */
    public static Map<Long, BigDecimal> issued(ProductionRequest r) {
        ProductionRequestRevision b = lastIssued(r);
        if (b == null) return null;
        Map<Long, BigDecimal> m = new LinkedHashMap<>();
        for (ProductionRequestRevision.Item i : b.getItems()) m.merge(i.getIngredientId(), i.getQuantity(), BigDecimal::add);
        return m;
    }

    public static Map<Long, BigDecimal> current(ProductionRequest r) {
        Map<Long, BigDecimal> m = new LinkedHashMap<>();
        for (ProductionRequestItem i : r.getItems()) m.merge(i.getIngredient().getId(), i.getRequiredQuantity(), BigDecimal::add);
        return m;
    }

    /** Разница «сейчас в заявке − выдано» по учитываемому сырью: плюс — довыдать, минус — вернуть. */
    public record Diff(Long ingredientId, String name, String unit, BigDecimal issued, BigDecimal current, BigDecimal d) {
    }

    public static List<Diff> diff(ProductionRequest r) {
        String k = key(r);
        if (!(k.equals("collected") || k.equals("received") || k.equals("done"))) return List.of();
        Map<Long, BigDecimal> iss = issued(r);
        if (iss == null) return List.of();
        Map<Long, BigDecimal> cur = current(r);
        Map<Long, String[]> names = new HashMap<>();
        for (ProductionRequestItem i : r.getItems()) names.put(i.getIngredient().getId(), new String[]{i.getIngredient().getName(), unit(i.getIngredient())});
        ProductionRequestRevision b = lastIssued(r);
        for (ProductionRequestRevision.Item i : b.getItems()) names.putIfAbsent(i.getIngredientId(), new String[]{i.getIngredientName(), i.getUnit()});
        LinkedHashSet<Long> keys = new LinkedHashSet<>(cur.keySet());
        keys.addAll(iss.keySet());
        List<Diff> out = new ArrayList<>();
        for (Long id : keys) {
            String[] nu = names.get(id);
            if (untracked(nu[0])) continue;
            BigDecimal c = cur.getOrDefault(id, BigDecimal.ZERO), is = iss.getOrDefault(id, BigDecimal.ZERO), d = c.subtract(is);
            if (d.abs().compareTo(new BigDecimal("0.0005")) > 0) out.add(new Diff(id, nu[0], nu[1], is, c, d));
        }
        return out;
    }

    /** Плашка «Состав изменён после выдачи»: замесы, что довыдать, что забрать. */
    public record DiffText(String batches, String give, String back) {
    }

    public static DiffText diffText(ProductionRequest r) {
        List<Diff> df = diff(r);
        String give = String.join(", ", df.stream().filter(x -> x.d().signum() > 0).map(x -> x.name() + " " + qu(x.d(), x.unit())).toList());
        String back = String.join(", ", df.stream().filter(x -> x.d().signum() < 0).map(x -> x.name() + " " + qu(x.d().negate(), x.unit())).toList());
        String bch = String.join(", ", batchChanges(r).stream().map(b -> b.product() + " " + qn(b.from()) + " → " + qn(b.to())).toList());
        return new DiffText(bch, give, back);
    }

    /** Замесы, изменённые после выдачи: изделие, было → стало. */
    public record BatchChange(String product, BigDecimal from, BigDecimal to) {
    }

    public static List<BatchChange> batchChanges(ProductionRequest r) {
        ProductionRequestRevision b = lastIssued(r);
        if (b == null || b.getLines().isEmpty()) return List.of();
        Map<Long, String> names = new LinkedHashMap<>();
        Map<Long, BigDecimal> was = new HashMap<>(), now = new HashMap<>();
        for (ProductionRequestRevision.Line l : b.getLines()) {
            names.put(l.getProductId(), l.getProductName());
            was.merge(l.getProductId(), nz(l.getBatches()), BigDecimal::add);
        }
        for (ProductionRequestLine l : r.getLines()) {
            names.put(l.getProduct().getId(), l.getProduct().getName());
            now.merge(l.getProduct().getId(), nz(l.getBatches()), BigDecimal::add);
        }
        List<BatchChange> out = new ArrayList<>();
        names.forEach((id, n) -> {
            BigDecimal a = was.getOrDefault(id, BigDecimal.ZERO), c = now.getOrDefault(id, BigDecimal.ZERO);
            if (a.compareTo(c) != 0) out.add(new BatchChange(shortName(n), a, c));
        });
        return out;
    }

    /** Новой заявке не хватает на складе: сырьё, нужно, есть. */
    public record Short(Ingredient ingredient, BigDecimal need, BigDecimal has) {
        public String text() {
            return ingredient.getName() + " " + qu(need.subtract(has), unit(ingredient));
        }
    }

    public static List<Short> stockShort(ProductionRequest r) {
        if (r.getStatus() != ProductionRequestStatus.NEW) return List.of();
        List<Short> out = new ArrayList<>();
        for (ProductionRequestItem it : r.getItems()) {
            Ingredient i = it.getIngredient();
            if (untracked(i)) continue;
            BigDecimal has = nz(i.getQuantityInStock());
            if (has.compareTo(it.getRequiredQuantity()) < 0) out.add(new Short(i, it.getRequiredQuantity(), has));
        }
        return out;
    }

    /** Сколько сырья нужно по всем новым заявкам (ещё не выданным): сырьё → количество и номера заявок. */
    public record Need(Ingredient ingredient, BigDecimal q, List<Long> requests) {
    }

    public static Map<Long, Need> needNew(Collection<ProductionRequest> all) {
        Map<Long, Need> out = new LinkedHashMap<>();
        for (ProductionRequest r : all) {
            if (r.getStatus() != ProductionRequestStatus.NEW) continue;
            for (ProductionRequestItem it : r.getItems()) {
                Need n = out.get(it.getIngredient().getId());
                List<Long> reqs = n == null ? new ArrayList<>() : new ArrayList<>(n.requests());
                if (!reqs.contains(r.getId())) reqs.add(r.getId());
                out.put(it.getIngredient().getId(), new Need(it.getIngredient(),
                        (n == null ? BigDecimal.ZERO : n.q()).add(it.getRequiredQuantity()), reqs));
            }
        }
        return out;
    }

    /** Оприходовано меньше нормы. */
    public static List<ProductionRequestLine> shortLines(ProductionRequest r) {
        return main(r).stream().filter(l -> l.isReceived() && l.getReceivedQuantity().compareTo(nz(l.getPlannedQuantity())) < 0).toList();
    }

    /** Причина отмены — из истории (кнопка «Отменить заявку» с причиной). */
    public static String offNote(ProductionRequest r) {
        String note = null;
        for (ProductionRequestRevision x : r.getRevisions()) {
            if (x.getNote() != null && x.getNote().startsWith("Отменена")) note = x.getNote();
        }
        if (note == null) return null;
        int c = note.indexOf(": ");
        return c < 0 ? null : note.substring(c + 2);
    }

    public static ProductionRequestRevision offRevision(ProductionRequest r) {
        ProductionRequestRevision out = null;
        for (ProductionRequestRevision x : r.getRevisions()) if (x.getNote() != null && x.getNote().startsWith("Отменена")) out = x;
        return out;
    }

    public static ProductionRequestRevision doneRevision(ProductionRequest r) {
        ProductionRequestRevision out = null;
        for (ProductionRequestRevision x : r.getRevisions()) if (x.getNote() != null && x.getNote().startsWith("Завершена")) out = x;
        return out;
    }

    /** Что дальше — подпись под статусом в списке. */
    public static String next(ProductionRequest r) {
        return switch (key(r)) {
            case "new" -> "ждёт склад сырья";
            case "collected" -> "ждёт оприхода · " + got(r) + " из " + main(r).size();
            case "received" -> {
                java.time.LocalDateTime at = com.confectionery.crm.service.ProductionRequestService.autoCompleteAt(r);
                yield at == null ? "ждёт «Завершить»" : "ждёт «Завершить» · сама " + F.dm(at.toLocalDate()) + " в " + at.toLocalTime();
            }
            case "done" -> "проведена в 1С";
            default -> {
                String n = offNote(r);
                yield n == null || n.isBlank() ? "отменена" : F.cap(n);
            }
        };
    }

    // ------------------------------------------------------------------ строка списка

    public record Flag(String cls, String text) {
    }

    public record Part(String name, BigDecimal batches) {
    }

    public record Row(ProductionRequest r, String key, Badge badge, List<Part> parts, int extra, BigDecimal batches, BigDecimal plan,
                      BigDecimal fact, int got, int main, List<Flag> flags, String next) {
        public boolean quiet() {
            return key.equals("done") || key.equals("off");
        }

        public String batchesWord() {
            long n = (long) Math.ceil(batches.doubleValue());
            return F.plural(n, "замес", "замеса", "замесов");
        }
    }

    public static Row row(ProductionRequest r) {
        String k = key(r);
        List<ProductionRequestLine> main = main(r);
        List<Part> parts = main.stream().map(l -> new Part(shortName(l.getProduct().getName()), l.getBatches())).toList();
        List<Flag> flags = new ArrayList<>();
        List<Short> ss = stockShort(r);
        if (!ss.isEmpty()) flags.add(new Flag("pay-bad", "не хватает: " + String.join(", ", ss.stream().map(Short::text).toList())));
        List<Diff> df = k.equals("done") || k.equals("off") ? List.of() : diff(r);
        if (!df.isEmpty()) flags.add(new Flag("q-y", "состав изменён после выдачи: " + df.size() + NB + F.plural(df.size(), "позиция", "позиции", "позиций")));
        List<ProductionRequestLine> sl = shortLines(r);
        if (!sl.isEmpty()) flags.add(new Flag("pay-bad", "недостаточно: " + String.join(", ", sl.stream()
                .map(l -> shortName(l.getProduct().getName()) + " — " + F.num(nz(l.getPlannedQuantity()).subtract(l.getReceivedQuantity())) + NB + "шт").toList())));
        if (r.getOneCSyncError() != null && !k.equals("off")) flags.add(new Flag("pay-bad", "не ушла в 1С"));
        return new Row(r, k, badge(k), parts, r.getLines().size() - main.size(), batches(r), plan(r), fact(r), got(r), main.size(), flags, next(r));
    }

    // ------------------------------------------------------------------ карточка

    public record Step(int n, String title, String sub, boolean done, boolean cur) {
    }

    /** Позиция «Что выпускаем»: факт, отклонение от нормы. */
    public record Out(ProductionRequestLine l, String name, String photo, boolean extra, BigDecimal yield, BigDecimal dev, String by) {
        public boolean received() {
            return l.isReceived();
        }
    }

    /** Строка сырья: новая заявка — чек-лист; после выдачи — норма, в заявке, выдано, разница. */
    public record Raw(Long itemId, Long ingredientId, String name, String unit, BigDecimal norm, BigDecimal current, BigDecimal issued,
                      BigDecimal diff, BigDecimal stock, boolean shortage, boolean free, String note, boolean removed) {
        public String q(BigDecimal v) {
            return qu(v, unit);
        }
    }

    public record Hist(LocalDateTime at, String by, String kind, String text) {
        public String icon() {
            return HK.getOrDefault(kind, HK.get("edit"))[0];
        }

        public String cls() {
            return HK.getOrDefault(kind, HK.get("edit"))[1];
        }
    }

    static final Map<String, String[]> HK = Map.ofEntries(
            Map.entry("new", new String[]{"s-new", "st--sent"}), Map.entry("1c", new String[]{"doc", "d-paper"}),
            Map.entry("err", new String[]{"alert", "d-crim"}), Map.entry("collect", new String[]{"materials", "st--work"}),
            Map.entry("edit", new String[]{"edit", "d-paper"}), Map.entry("settle", new String[]{"check", "d-yolk"}),
            Map.entry("receive", new String[]{"stock", "st--ready"}), Map.entry("done", new String[]{"s-done", "st--done"}),
            Map.entry("off", new String[]{"s-off", "st--off"}), Map.entry("undone", new String[]{"back", "d-paper"}),
            Map.entry("brig", new String[]{"clients", "d-paper"}));

    public static List<Step> steps(ProductionRequest r, Function<String, String> who) {
        int got = got(r), n = main(r).size();
        boolean received = r.getStatus() == ProductionRequestStatus.RECEIVED;
        LocalDateTime recAt = r.getLines().stream().map(ProductionRequestLine::getReceivedAt).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
        ProductionRequestRevision dr = doneRevision(r);
        List<Object[]> s = List.of(
                new Object[]{"Создана", F.when(r.getCreatedAt()) + " · " + who.apply(r.getCreatedBy()), true},
                new Object[]{"Сырьё выдано", r.getCollectedAt() != null ? F.when(r.getCollectedAt()) + " · " + who.apply(r.getCollectedBy()) : "ждёт склад сырья", r.getCollectedAt() != null},
                new Object[]{"Оприходовано", received ? (recAt != null ? F.when(recAt) + " · " : "") + "все позиции" : got > 0 ? got + " из " + n + " — ждёт склад ГП" : "ждёт склад ГП", received},
                new Object[]{"Завершена", r.isRequisitionCompleted() ? (dr != null ? F.when(dr.getCreatedAt()) + " · " + who.apply(dr.getCreatedBy()) : "требование-накладная проведена") : "требование-накладная в 1С", r.isRequisitionCompleted()});
        int cur = -1;
        for (int i = 0; i < s.size(); i++) if (!(Boolean) s.get(i)[2]) { cur = i; break; }
        List<Step> out = new ArrayList<>();
        for (int i = 0; i < s.size(); i++) out.add(new Step(i + 1, (String) s.get(i)[0], (String) s.get(i)[1], (Boolean) s.get(i)[2], i == cur));
        return out;
    }

    public static List<Out> outs(ProductionRequest r, Function<Long, Recipe> recipeOf, Function<String, String> who) {
        List<Out> out = new ArrayList<>();
        for (ProductionRequestLine l : r.getLines()) {
            boolean ex = extra(l);
            Recipe rc = recipeOf.apply(l.getProduct().getId());
            BigDecimal dev = l.isReceived() && !ex ? l.getReceivedQuantity().subtract(nz(l.getPlannedQuantity())) : BigDecimal.ZERO;
            out.add(new Out(l, l.getProduct().getName(), l.getProduct().getImageUrl(), ex, rc == null ? null : rc.getYieldQuantity(), dev,
                    l.getReceivedBy() == null ? "" : who.apply(l.getReceivedBy())));
        }
        return out;
    }

    /** Пометка у строки сырья: замена, добавлено, отклонение от нормы. */
    static String note(ProductionRequestItem it, Map<Long, BigDecimal> norm, Map<String, BigDecimal> normByName) {
        if (it.getOriginalIngredientName() != null) return "замена: было " + it.getOriginalIngredientName();
        BigDecimal n = norm.get(it.getIngredient().getId());
        if (n == null) return "добавлено — нет в рецептуре";
        BigDecimal d = it.getRequiredQuantity().subtract(n);
        if (d.abs().compareTo(new BigDecimal("0.0005")) > 0) return (d.signum() > 0 ? "+" : "−") + qu(d.abs(), unit(it.getIngredient())) + " к норме";
        return "";
    }

    public static List<Raw> raw(ProductionRequest r, Function<Long, Recipe> recipeOf) {
        Map<Ingredient, BigDecimal> nm = norm(r, recipeOf);
        Map<Long, BigDecimal> norm = new LinkedHashMap<>();
        Map<String, BigDecimal> normByName = new HashMap<>();
        Map<Long, Ingredient> normIng = new LinkedHashMap<>();
        nm.forEach((i, q) -> {
            norm.put(i.getId(), q);
            normByName.put(i.getName().trim().toLowerCase(Locale.ROOT), q);
            normIng.put(i.getId(), i);
        });
        String k = key(r);
        List<Raw> out = new ArrayList<>();
        if (k.equals("new") || k.equals("off")) {
            for (ProductionRequestItem it : r.getItems()) {
                Ingredient i = it.getIngredient();
                boolean free = untracked(i);
                BigDecimal stock = nz(i.getQuantityInStock());
                out.add(new Raw(it.getId(), i.getId(), i.getName(), unit(i), norm.get(i.getId()), it.getRequiredQuantity(), null, null, stock,
                        !free && stock.compareTo(it.getRequiredQuantity()) < 0, free, note(it, norm, normByName), false));
            }
            return out;
        }
        Map<Long, BigDecimal> iss = issued(r);
        if (iss == null) iss = current(r);
        Set<Long> seen = new HashSet<>();
        for (ProductionRequestItem it : r.getItems()) {
            Ingredient i = it.getIngredient();
            seen.add(i.getId());
            BigDecimal n = norm.get(i.getId());
            if (n == null && it.getOriginalIngredientName() != null) n = normByName.get(it.getOriginalIngredientName().trim().toLowerCase(Locale.ROOT));
            BigDecimal is = iss.get(i.getId());
            boolean free = untracked(i);
            BigDecimal d = free ? BigDecimal.ZERO : it.getRequiredQuantity().subtract(is == null ? BigDecimal.ZERO : is);
            out.add(new Raw(it.getId(), i.getId(), i.getName(), unit(i), n, it.getRequiredQuantity(), is, d, nz(i.getQuantityInStock()),
                    false, free, note(it, norm, normByName), false));
        }
        ProductionRequestRevision b = lastIssued(r);
        if (b != null) {
            for (ProductionRequestRevision.Item x : b.getItems()) {
                if (seen.contains(x.getIngredientId())) continue;
                seen.add(x.getIngredientId());
                boolean free = untracked(x.getIngredientName());
                BigDecimal is = iss.getOrDefault(x.getIngredientId(), BigDecimal.ZERO);
                out.add(new Raw(null, x.getIngredientId(), x.getIngredientName(), x.getUnit(), norm.get(x.getIngredientId()), BigDecimal.ZERO, is,
                        free ? BigDecimal.ZERO : is.negate(), null, false, free, "убрано из заявки", true));
            }
        }
        return out;
    }

    /** История заявки: создание, 1С, правки и выдача (ProductionRequestRevision), оприходование по строкам. */
    public static List<Hist> history(ProductionRequest r, Function<String, String> who) {
        List<Hist> h = new ArrayList<>();
        h.add(new Hist(r.getCreatedAt(), who.apply(r.getCreatedBy()), "new", "Создана: " + String.join(", ",
                main(r).stream().map(l -> shortName(l.getProduct().getName()) + " — " + zam(l.getBatches())).toList())));
        if (r.getOneCReportNumber() != null || r.getOneCRequisitionNumber() != null) {
            h.add(new Hist(r.getCreatedAt(), "Система", "1c", "В 1С созданы отчёт производства и требование-накладная"));
        }
        boolean collectLogged = false;
        for (ProductionRequestRevision x : r.getRevisions()) {
            String t = x.getNote() == null ? "" : x.getNote();
            String kind = t.startsWith("Собрано") ? "collect" : t.startsWith("Рассчитались") ? "settle" : t.startsWith("Завершена") ? "done"
                    : t.startsWith("Завершение отменено") ? "undone" : t.startsWith("Отменена") ? "off" : t.startsWith("Бригада") ? "brig" : "edit";
            if (kind.equals("collect")) {
                collectLogged = true;
                t = "Собрано — сырьё выдано в цех и списано со склада";
            }
            h.add(new Hist(x.getCreatedAt(), who.apply(x.getCreatedBy()), kind, t));
        }
        if (!collectLogged && r.getCollectedAt() != null) {
            h.add(new Hist(r.getCollectedAt(), who.apply(r.getCollectedBy()), "collect", "Собрано — сырьё выдано в цех и списано со склада"));
        }
        for (ProductionRequestLine l : r.getLines()) {
            if (l.getReceivedAt() == null) continue;
            h.add(new Hist(l.getReceivedAt(), who.apply(l.getReceivedBy()), "receive", "Оприходовано: " + shortName(l.getProduct().getName()) + " — "
                    + F.num(l.getReceivedQuantity()) + NB + "шт" + (extra(l) ? " (сверх заявки)" : " (норма " + F.num(nz(l.getPlannedQuantity())) + ")")));
        }
        if (r.getStatus() == ProductionRequestStatus.RECEIVED && r.getOneCReportNumber() != null) {
            r.getLines().stream().map(ProductionRequestLine::getReceivedAt).filter(Objects::nonNull).max(Comparator.naturalOrder())
                    .ifPresent(at -> h.add(new Hist(at, "Система", "1c", "Отчёт производства проведён в 1С — весь выпуск оприходован")));
        }
        if (r.getOneCSyncError() != null && r.getStatus() != ProductionRequestStatus.CANCELLED) {
            h.add(new Hist(LocalDateTime.of(9999, 1, 1, 0, 0), "1С", "err", "Не ушла в 1С: " + r.getOneCSyncError()));
        }
        List<Hist> sorted = new ArrayList<>(h);
        sorted.sort(Comparator.comparing(Hist::at));
        return sorted;
    }

    // ------------------------------------------------------------------ поиск

    /** Для поиска: строчные, ё → е. */
    public static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replace('ё', 'е').trim();
    }

    /** № заявки, группа или изделие — каждое слово должно найтись. */
    public static boolean matches(ProductionRequest r, String[] words) {
        if (words.length == 0) return true;
        StringBuilder hay = new StringBuilder(r.getId() + " " + r.getTitle());
        for (ProductionRequestLine l : r.getLines()) hay.append(' ').append(l.getProduct().getName());
        String h = norm(hay.toString());
        for (String w : words) if (!h.contains(w.replaceFirst("^№", ""))) return false;
        return true;
    }

    // ------------------------------------------------------------------ данные для окон и формы (JSON в странице)

    static double d(BigDecimal v) {
        return v == null ? 0 : v.doubleValue();
    }

    static Map<String, Object> ing(Ingredient i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", i.getId());
        m.put("name", i.getName());
        m.put("unit", unit(i));
        m.put("stock", d(i.getQuantityInStock()));
        m.put("free", untracked(i));
        return m;
    }

    /** «Общая рецептура»: новые заявки и их сырьё, остатки склада. */
    public static Map<String, Object> mergeData(Collection<ProductionRequest> all) {
        List<Map<String, Object>> pool = new ArrayList<>();
        Map<Long, Map<String, Object>> ings = new LinkedHashMap<>();
        all.stream().filter(r -> r.getStatus() == ProductionRequestStatus.NEW).sorted(Comparator.comparing(ProductionRequest::getId)).forEach(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("title", r.getTitle());
            m.put("batches", d(batches(r)));
            List<Object[]> items = new ArrayList<>();
            for (ProductionRequestItem it : r.getItems()) {
                items.add(new Object[]{it.getIngredient().getId(), d(it.getRequiredQuantity())});
                ings.putIfAbsent(it.getIngredient().getId(), ing(it.getIngredient()));
            }
            m.put("items", items);
            pool.add(m);
        });
        return Map.of("pool", pool, "ings", ings);
    }

    /** Карточка: сырьё для «Добавить» / «Заменить», изделия для «сверх заявки», норма по сырью. */
    public static Map<String, Object> cardData(ProductionRequest r, List<Ingredient> active, Collection<Recipe> recipes) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("k", key(r));
        m.put("issued", r.getStatus() == ProductionRequestStatus.COLLECTED || r.getStatus() == ProductionRequestStatus.RECEIVED);
        m.put("ings", active.stream().filter(i -> !untracked(i)).sorted(Comparator.comparing(Ingredient::getName, String.CASE_INSENSITIVE_ORDER))
                .map(AdminRequests::ing).toList());
        Set<Long> inReq = new HashSet<>();
        r.getLines().forEach(l -> inReq.add(l.getProduct().getId()));
        m.put("prods", recipes.stream().map(Recipe::getProduct).filter(p -> p != null && p.isActive() && !inReq.contains(p.getId()))
                .sorted(Comparator.comparing(Product::getName, String.CASE_INSENSITIVE_ORDER))
                .map(p -> Map.of("id", p.getId(), "name", p.getName())).toList());
        Map<Long, Recipe> byProduct = new HashMap<>();
        recipes.forEach(rc -> { if (rc.getProduct() != null) byProduct.put(rc.getProduct().getId(), rc); });
        Map<Long, Double> nm = new LinkedHashMap<>();
        norm(r, byProduct::get).forEach((i, q) -> nm.put(i.getId(), d(q)));
        m.put("norm", nm);
        return m;
    }

    /** Рецептура годится для заявки: есть выход и состав. */
    public static boolean usable(Recipe rc) {
        return rc.getProduct() != null && rc.getProduct().isActive() && rc.getYieldQuantity() != null && rc.getYieldQuantity().signum() > 0
                && !rc.getItems().isEmpty();
    }

    public static String group(Product p) {
        return p.getGroup() == null || p.getGroup().isBlank() ? "Без группы" : p.getGroup();
    }

    /** Новая заявка / изменить: изделия с рецептурой, сырьё со складом, что ждут другие новые заявки. */
    public static Map<String, Object> formData(ProductionRequest r, ProductionRequest repeat, Collection<Recipe> recipes, List<Ingredient> active,
                                               Collection<ProductionRequest> all, List<com.confectionery.crm.service.ProductionRequestService.AdjustmentRow> adj) {
        Map<String, Object> m = new LinkedHashMap<>();
        java.time.LocalDate today = java.time.LocalDate.now();
        m.put("today", today.toString());
        m.put("min", today.minusDays(30).toString());
        m.put("max", today.plusDays(7).toString());
        m.put("no", r == null ? null : r.getId());
        m.put("doc", r == null ? today.toString() : r.getDocumentDate().toString());
        m.put("group", r == null ? null : r.getTitle());
        m.put("issued", r != null && r.getStatus() == ProductionRequestStatus.COLLECTED);
        ProductionRequest src = r != null ? r : repeat;
        List<Map<String, Object>> rows = new ArrayList<>();
        if (src != null) for (ProductionRequestLine l : main(src)) rows.add(Map.of("p", l.getProduct().getId(), "b", d(l.getBatches())));
        m.put("rows", rows);
        List<Map<String, Object>> prods = new ArrayList<>();
        Map<Long, Map<String, Object>> ings = new LinkedHashMap<>();
        recipes.stream().filter(AdminRequests::usable).sorted(Comparator.comparing(rc -> rc.getProduct().getName(), String.CASE_INSENSITIVE_ORDER)).forEach(rc -> {
            Product p = rc.getProduct();
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", p.getId());
            x.put("name", p.getName());
            x.put("group", group(p));
            x.put("yield", d(rc.getYieldQuantity()));
            x.put("photo", p.getImageUrl());
            List<Object[]> rr = new ArrayList<>();
            for (RecipeItem ri : rc.getItems()) {
                rr.add(new Object[]{ri.getIngredient().getId(), d(ri.getQuantity())});
                ings.putIfAbsent(ri.getIngredient().getId(), ing(ri.getIngredient()));
            }
            x.put("rows", rr);
            prods.add(x);
        });
        m.put("prods", prods);
        m.put("ings", ings);
        Map<Long, Double> need = new LinkedHashMap<>();
        List<Long> newNos = new ArrayList<>();
        for (ProductionRequest o : all) {
            if (o.getStatus() != ProductionRequestStatus.NEW || (r != null && o.getId().equals(r.getId()))) continue;
            newNos.add(o.getId());
            for (ProductionRequestItem it : o.getItems()) need.merge(it.getIngredient().getId(), d(it.getRequiredQuantity()), Double::sum);
        }
        Collections.sort(newNos);
        m.put("need", need);
        m.put("newNos", newNos);
        m.put("adj", adj.stream().map(a -> Map.of("name", a.ingredientName(), "unit", a.unit() == null ? "кг" : a.unit(),
                "d", d(a.delta()), "note", a.note() == null ? "" : a.note())).toList());
        return m;
    }
}
