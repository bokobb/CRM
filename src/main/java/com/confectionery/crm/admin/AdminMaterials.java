package com.confectionery.crm.admin;

import com.confectionery.crm.domain.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Склад сырья по макету (часть 2): состояние позиции («Не хватит на заявки», «На исходе», «Нет», «Есть»,
 * «Не учитывается» — вода и лёд), «Хватит на» по среднему расходу в цех за две недели, цена последней
 * покупки, журнал движений одной строкой на документ (выдача по заявке, приход по покупке, документы
 * «Поступление»/«Списание», возврат из цеха). Всё из Ingredient, StockMovement, Purchase, ProductionRequest.
 */
public final class AdminMaterials {

    private AdminMaterials() {
    }

    static final AdminFormat F = new AdminFormat();
    static final String NB = AdminFormat.NB;
    /** Окно для расхода в цех: полные рабочие дни до сегодня. */
    public static final int USE_DAYS = 14;
    public static final int STALE_DAYS = 45;

    // ------------------------------------------------------------------ состояние и расход

    public record State(String key, String label, String cls) {
    }

    static final Map<String, State> ST = Map.of(
            "short", new State("short", "Не хватит на заявки", "ms--short"),
            "low", new State("low", "На исходе", "ms--low"),
            "zero", new State("zero", "Нет", "ms--zero"),
            "ok", new State("ok", "Есть", "ms--ok"),
            "free", new State("free", "Не учитывается", "ms--free"));

    public static State state(Ingredient m, AdminRequests.Need need) {
        if (AdminRequests.untracked(m)) return ST.get("free");
        BigDecimal stock = AdminRequests.nz(m.getQuantityInStock());
        if (need != null && need.q().compareTo(stock) > 0) return ST.get("short");
        if (stock.signum() <= 0) return ST.get("zero");
        BigDecimal min = AdminRequests.nz(m.getMinThreshold());
        if (min.signum() > 0 && stock.compareTo(min) < 0) return ST.get("low");
        return ST.get("ok");
    }

    static int rank(State s) {
        return switch (s.key()) {
            case "short" -> 0;
            case "zero" -> 1;
            case "low" -> 2;
            case "ok" -> 3;
            default -> 4;
        };
    }

    /** Рабочие дни (без воскресений) в окне расхода. */
    public static int workDays(LocalDate today) {
        int n = 0;
        for (LocalDate d = today.minusDays(USE_DAYS); d.isBefore(today); d = d.plusDays(1)) if (d.getDayOfWeek() != DayOfWeek.SUNDAY) n++;
        return Math.max(1, n);
    }

    /** Выдача в цех по заявкам минус возвраты из цеха — за окно до сегодня, в среднем за рабочий день. */
    public static Map<Long, BigDecimal> usage(List<StockMovement> moves, LocalDate today) {
        LocalDateTime from = today.minusDays(USE_DAYS).atStartOfDay(), to = today.atStartOfDay();
        Map<Long, BigDecimal> sum = new HashMap<>();
        for (StockMovement x : moves) {
            if (x.getIngredient() == null || x.isManual() || x.getCreatedAt() == null) continue;
            if (x.getCreatedAt().isBefore(from) || !x.getCreatedAt().isBefore(to)) continue;
            BigDecimal q = x.getType() == StockMovementType.WRITE_OFF ? x.getQuantity() : x.getQuantity().negate();
            sum.merge(x.getIngredient().getId(), q, BigDecimal::add);
        }
        BigDecimal days = BigDecimal.valueOf(workDays(today));
        Map<Long, BigDecimal> out = new HashMap<>();
        sum.forEach((k, v) -> { if (v.signum() > 0) out.put(k, v.divide(days, 6, RoundingMode.HALF_UP)); });
        return out;
    }

    public static Double daysLeft(Ingredient m, BigDecimal use) {
        if (AdminRequests.untracked(m) || use == null || use.signum() <= 0) return null;
        return AdminRequests.nz(m.getQuantityInStock()).doubleValue() / use.doubleValue();
    }

    public record Days(String text, boolean bad) {
    }

    public static Days daysText(Double d) {
        if (d == null) return new Days("—", false);
        if (d < 1) return new Days("меньше дня", true);
        if (d > 30) return new Days("больше 30" + NB + "дн.", false);
        long n = (long) Math.floor(d);
        return new Days("≈" + NB + n + NB + F.plural(n, "день", "дня", "дней"), false);
    }

    // ------------------------------------------------------------------ цена последней покупки

    public record Price(BigDecimal unit, LocalDate date, Long purchaseId, String supplier) {
        public boolean stale() {
            return date != null && date.isBefore(LocalDate.now().minusDays(STALE_DAYS));
        }
    }

    /** Строки покупок новые первыми → первая по каждому сырью. */
    public static Map<Long, Price> prices(List<PurchaseItem> newestFirst) {
        Map<Long, Price> out = new HashMap<>();
        for (PurchaseItem it : newestFirst) {
            if (it.getIngredient() == null || it.getTotalPrice() == null || it.getQuantity() == null || it.getQuantity().signum() <= 0) continue;
            Long id = it.getIngredient().getId();
            if (out.containsKey(id)) continue;
            Purchase p = it.getPurchase();
            out.put(id, new Price(it.getTotalPrice().divide(it.getQuantity(), 2, RoundingMode.HALF_UP), p == null ? null : p.getPurchaseDate(),
                    p == null ? null : p.getId(), p == null || p.getCounterparty() == null ? null : p.getCounterparty().getName()));
        }
        return out;
    }

    // ------------------------------------------------------------------ строка списка

    public record Row(Ingredient m, State s, AdminRequests.Need need, BigDecimal use, Double days, Price price) {
        public boolean free() {
            return s.key().equals("free");
        }

        public boolean bad() {
            return s.key().equals("short") || s.key().equals("low") || s.key().equals("zero");
        }

        public boolean needShort() {
            return need != null && need.q().compareTo(AdminRequests.nz(m.getQuantityInStock())) > 0;
        }

        public String unit() {
            return AdminRequests.unit(m);
        }

        public String q(BigDecimal v) {
            return AdminRequests.qu(v, unit());
        }

        public Days daysText() {
            return AdminMaterials.daysText(days);
        }

        public String needNos() {
            return need == null ? "" : "№\u00a0" + String.join(", ", need.requests().stream().sorted().map(String::valueOf).toList());
        }
    }

    public static List<Row> rows(List<Ingredient> list, Map<Long, AdminRequests.Need> need, Map<Long, BigDecimal> use, Map<Long, Price> prices) {
        List<Row> out = new ArrayList<>();
        for (Ingredient m : list) {
            AdminRequests.Need n = need.get(m.getId());
            BigDecimal u = use.get(m.getId());
            out.add(new Row(m, state(m, n), n, u, daysLeft(m, u), prices.get(m.getId())));
        }
        return out;
    }

    public static Comparator<Row> sort(String key) {
        Comparator<Row> byName = Comparator.comparing(r -> r.m().getName(), String.CASE_INSENSITIVE_ORDER);
        return switch (key) {
            case "days" -> Comparator.<Row>comparingDouble(r -> r.days() == null ? 1e9 : r.days()).thenComparing(byName);
            case "name" -> byName;
            case "sup" -> Comparator.<Row, String>comparing(r -> r.price() == null || r.price().supplier() == null ? "яяя" : r.price().supplier(),
                    String.CASE_INSENSITIVE_ORDER).thenComparing(byName);
            default -> Comparator.<Row>comparingInt(r -> rank(r.s()))
                    .thenComparingDouble(r -> r.s().key().equals("low") ? AdminRequests.nz(r.m().getQuantityInStock()).doubleValue()
                            / Math.max(1e-9, AdminRequests.nz(r.m().getMinThreshold()).doubleValue()) : 0)
                    .thenComparing(byName);
        };
    }

    // ------------------------------------------------------------------ журнал движений

    public record Kind(String key, String label, String cls) {
    }

    static final Map<String, Kind> KINDS = Map.of(
            "in", new Kind("in", "Приход", "mt--in"), "out", new Kind("out", "Выдано в цех", "mt--out"),
            "back", new Kind("back", "Возврат из цеха", "mt--back"), "off", new Kind("off", "Списание", "mt--off"));

    public static Kind kind(StockMovement x) {
        boolean in = x.getType() == StockMovementType.RECEIPT;
        if (!x.isManual()) return KINDS.get(in ? "back" : "out");
        return KINDS.get(in ? "in" : "off");
    }

    static final Pattern REQ = Pattern.compile("Заявка №\\s*(\\d+)(?:\\s*«([^»]*)»)?(?:\\s*—\\s*(.*))?");

    /** Движение: вид, документ (заявка, покупка, «Поступление/Списание № …»), подпись под документом, кто. */
    public record Move(StockMovement x, Kind kind, String doc, String href, String sub, String by) {
        public boolean plus() {
            return x.getType() == StockMovementType.RECEIPT;
        }

        public String unit() {
            return x.getIngredient() != null ? AdminRequests.unit(x.getIngredient())
                    : x.getProduct() != null && x.getProduct().getUnit() != null && !x.getProduct().getUnit().isBlank() ? x.getProduct().getUnit() : "шт";
        }

        public String qty() {
            return (plus() ? "+" : "−") + AdminRequests.qu(x.getQuantity(), unit());
        }

        public String name() {
            return x.getIngredient() != null ? x.getIngredient().getName() : x.getProduct() != null ? x.getProduct().getName() : "—";
        }
    }

    public static Move move(StockMovement x, Map<Long, Purchase> byMovement, Function<String, String> who) {
        Kind k = kind(x);
        String reason = x.getReason() == null ? "" : x.getReason();
        Purchase p = byMovement.get(x.getId());
        if (p != null) {
            return new Move(x, k, "Покупка № " + p.getId(), "/admin/purchases/" + p.getId(),
                    p.getCounterparty() == null ? "" : p.getCounterparty().getName(), who.apply(x.getCreatedBy()));
        }
        Matcher mt = REQ.matcher(reason);
        if (mt.find()) {
            String note = mt.group(3);
            return new Move(x, k, "Заявка № " + mt.group(1) + (mt.group(2) != null ? " «" + mt.group(2) + "»" : ""), "/admin/requests/" + mt.group(1),
                    note != null && !note.isBlank() ? note : k.key().equals("out") ? "выдано в цех" : k.key().equals("back") ? "вернули на склад" : "",
                    who.apply(x.getCreatedBy()));
        }
        StockDocument d = x.getDocument();
        if (d != null) {
            return new Move(x, k, (d.getType() == StockMovementType.WRITE_OFF ? "Списание" : "Поступление") + " № " + d.getId(), null,
                    d.getReason() == null ? "" : d.getReason(), who.apply(x.getCreatedBy()));
        }
        if (reason.startsWith("Закупка у ")) return new Move(x, k, "Покупка", null, reason.substring("Закупка у ".length()), who.apply(x.getCreatedBy()));
        return new Move(x, k, k.key().equals("in") ? "Поступление" : k.key().equals("off") ? "Списание" : k.label(), null, reason, who.apply(x.getCreatedBy()));
    }

    /** Одна строка на документ: «13 позиций» раскрываются. */
    public record Group(String key, Move first, List<Move> items) {
        public boolean single() {
            return items.size() == 1;
        }

        public String count() {
            return F.count(items.size(), "позиция", "позиции", "позиций");
        }

        public String qty() {
            BigDecimal s = items.stream().map(m -> m.x().getQuantity()).reduce(BigDecimal.ZERO, BigDecimal::add);
            return (first.plus() ? "+" : "−") + AdminRequests.qu(s, first.unit());
        }
    }

    public record Day(LocalDate day, String title, int count, List<Group> groups) {
        public String countText() {
            return F.count(count, "движение", "движения", "движений");
        }
    }

    public static List<Day> days(List<Move> moves) {
        Map<LocalDate, List<Group>> byDay = new LinkedHashMap<>();
        Map<LocalDate, Integer> cnt = new HashMap<>();
        Map<String, Group> idx = new HashMap<>();
        for (Move m : moves) {
            LocalDateTime at = m.x().getCreatedAt();
            LocalDate d = at.toLocalDate();
            cnt.merge(d, 1, Integer::sum);
            String k = at.withSecond(0).withNano(0) + "|" + m.kind().key() + "|" + m.doc() + "|" + m.sub();
            Group g = idx.get(k);
            if (g == null) {
                g = new Group(k, m, new ArrayList<>());
                idx.put(k, g);
                byDay.computeIfAbsent(d, x -> new ArrayList<>()).add(g);
            }
            g.items().add(m);
        }
        LocalDate today = LocalDate.now();
        List<Day> out = new ArrayList<>();
        byDay.forEach((d, gs) -> out.add(new Day(d, F.dayTitle(d) + (d.equals(today) ? " · сегодня" : d.equals(today.minusDays(1)) ? " · вчера" : ""),
                cnt.get(d), gs)));
        return out;
    }

    /** Поиск в журнале: сырьё, № заявки или покупки, документ, примечание. */
    public static boolean matches(Move m, String[] words) {
        if (words.length == 0) return true;
        String hay = AdminRequests.norm(m.name() + " " + m.doc() + " " + m.sub() + " " + m.doc().replace("Заявка", "заявка").replace("Покупка", "покупка"));
        for (String w : words) if (!hay.contains(w.replaceFirst("^№", ""))) return false;
        return true;
    }

    // ------------------------------------------------------------------ документ «Поступление / Списание»

    public static final List<String> IN_WHY = List.of("Излишек при пересчёте", "Перемещение с другого склада", "Вернули из цеха без заявки", "Другое");
    public static final List<String> OFF_WHY = List.of("Порча: истёк срок", "Порча: хранение", "Недостача по пересчёту", "Брак в цеху", "Другое");

    public static Map<String, Object> docData(List<Ingredient> active, Long preset) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("today", LocalDate.now().toString());
        m.put("items", active.stream().filter(i -> !AdminRequests.untracked(i)).sorted(Comparator.comparing(Ingredient::getName, String.CASE_INSENSITIVE_ORDER))
                .map(i -> Map.of("id", i.getId(), "name", i.getName(), "unit", AdminRequests.unit(i), "stock", AdminRequests.d(i.getQuantityInStock()))).toList());
        m.put("inWhy", IN_WHY);
        m.put("offWhy", OFF_WHY);
        m.put("preset", preset);
        return m;
    }
}
