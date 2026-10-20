package com.confectionery.crm.admin;

import com.confectionery.crm.domain.*;
import com.confectionery.crm.repository.UserRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;

/**
 * Склад готовой продукции по макету (часть 3): остаток, резерв под открытые заказы (те же статусы, что «В заказах»
 * в отчёте «Оприход за смену»), свободно, что в производстве (план новых и собранных заявок, ещё не оприходованный),
 * сегодня и последний выпуск; журнал — из цеха (оприходование заявок), выдача заказов (отгрузка через CRM меняет
 * остаток), документы «Поступление»/«Списание», исправления оприходования.
 */
public final class AdminStock {

    private AdminStock() {
    }

    static final AdminFormat F = new AdminFormat();
    static final String NB = AdminFormat.NB;

    /** Открытые заказы: товар в них зарезервирован (ReportService.OPEN). */
    public static final List<OrderStatus> OPEN = List.of(OrderStatus.PLACED, OrderStatus.CONFIRMED, OrderStatus.NEW,
            OrderStatus.IN_PRODUCTION, OrderStatus.READY, OrderStatus.NO_SHOW);

    static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /** Отгрузка через CRM (гос. номер) списывает остаток; заказы, пришедшие из 1С проведёнными, — нет (OrderService.ship). */
    public static boolean movesStock(Order o) {
        return o.getStatus().isShipped() && o.getShippedAt() != null && o.getVehicleNumber() != null;
    }

    // ================================================================== резерв и в работе

    public record Res(BigDecimal q, BigDecimal late, BigDecimal today, BigDecimal tomorrow, BigDecimal later, List<Long> orders) {
        public String text() {
            List<String> out = new ArrayList<>();
            if (late.signum() > 0) out.add("не забрали " + F.num(late));
            if (today.signum() > 0) out.add("сегодня " + F.num(today));
            if (tomorrow.signum() > 0) out.add("завтра " + F.num(tomorrow));
            if (later.signum() > 0) out.add("позже " + F.num(later));
            return String.join(" · ", out);
        }
    }

    public static Map<Long, Res> reserve(List<Order> open, LocalDate today) {
        Map<Long, BigDecimal[]> acc = new HashMap<>();
        Map<Long, List<Long>> ords = new HashMap<>();
        for (Order o : open) {
            if (!OPEN.contains(o.getStatus())) continue;
            LocalDate d = AdminOrders.day(o);
            int b = d.isBefore(today) ? 0 : d.equals(today) ? 1 : d.equals(today.plusDays(1)) ? 2 : 3;
            for (OrderItem i : o.getItems()) {
                BigDecimal[] a = acc.computeIfAbsent(i.getProduct().getId(), k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
                a[b] = a[b].add(nz(i.getQuantity()));
                List<Long> l = ords.computeIfAbsent(i.getProduct().getId(), k -> new ArrayList<>());
                if (!l.contains(o.getId())) l.add(o.getId());
            }
        }
        Map<Long, Res> out = new HashMap<>();
        acc.forEach((k, a) -> out.put(k, new Res(a[0].add(a[1]).add(a[2]).add(a[3]), a[0], a[1], a[2], a[3], ords.get(k))));
        return out;
    }

    public record Work(BigDecimal q, List<Long> requests) {
        public String nos() {
            return "№" + NB + String.join(", ", requests.stream().sorted().map(String::valueOf).toList());
        }
    }

    /** В производстве: план строк новых и собранных заявок, ещё не оприходованных (без «сверх заявки»). */
    public static Map<Long, Work> inWork(List<ProductionRequest> requests) {
        Map<Long, BigDecimal> q = new HashMap<>();
        Map<Long, List<Long>> nos = new HashMap<>();
        for (ProductionRequest r : requests) {
            if (r.getStatus() != ProductionRequestStatus.NEW && r.getStatus() != ProductionRequestStatus.COLLECTED) continue;
            for (ProductionRequestLine l : r.getLines()) {
                if (AdminRequests.extra(l) || l.isReceived()) continue;
                q.merge(l.getProduct().getId(), nz(l.getPlannedQuantity()), BigDecimal::add);
                List<Long> n = nos.computeIfAbsent(l.getProduct().getId(), k -> new ArrayList<>());
                if (!n.contains(r.getId())) n.add(r.getId());
            }
        }
        Map<Long, Work> out = new HashMap<>();
        q.forEach((k, v) -> out.put(k, new Work(v, nos.get(k))));
        return out;
    }

    // ================================================================== журнал

    /** Логин → ФИО сотрудника для колонки «Кто» (один запрос на логин). */
    public static Function<String, String> who(UserRepository users) {
        Map<String, String> cache = new HashMap<>();
        return u -> {
            if (u == null || u.isBlank()) return "—";
            return cache.computeIfAbsent(u, k -> users.findByUsername(k)
                    .map(x -> x.getFullName() != null && !x.getFullName().isBlank() ? x.getFullName() : x.getUsername()).orElse(k));
        };
    }

    public record Kind(String key, String label, String cls) {
    }

    public static final Map<String, Kind> KINDS = Map.of(
            "prod", new Kind("prod", "Из цеха", "mt--in"), "ship", new Kind("ship", "Выдан заказ", "mt--out"),
            "in", new Kind("in", "Поступление", "mt--in"), "off", new Kind("off", "Списание", "mt--off"),
            "unprod", new Kind("unprod", "Отмена оприхода", "mt--off"));

    /** Движение товара: из журнала StockMovement или строка отгруженного заказа. del — id строки, которую можно удалить. */
    public record Move(LocalDateTime at, Product p, Kind kind, BigDecimal q, String doc, String href, String sub, String by, Long del, String groupKey) {
        public boolean plus() {
            return kind.key().equals("prod") || kind.key().equals("in");
        }

        public String qty() {
            return (plus() ? "+" : "−") + F.num(q) + NB + "шт";
        }

        public String qcls() {
            return plus() ? "mq--in" : kind.key().equals("ship") ? "" : "mq--off";
        }
    }

    public static Move move(StockMovement x, Function<String, String> who) {
        boolean in = x.getType() == StockMovementType.RECEIPT;
        String reason = x.getReason() == null ? "" : x.getReason();
        String by = who.apply(x.getCreatedBy());
        if (!x.isManual()) {
            Matcher mt = AdminMaterials.REQ.matcher(reason);
            String no = null, title = null, note = null;
            if (mt.find()) {
                no = mt.group(1);
                title = mt.group(2);
                note = mt.group(3);
            }
            Kind k = KINDS.get(in ? "prod" : "unprod");
            String doc = no == null ? (in ? "Оприходование" : "Отмена оприходования") : "Заявка № " + no + (title != null ? " «" + title + "»" : "");
            return new Move(x.getCreatedAt(), x.getProduct(), k, x.getQuantity(), doc, no == null ? null : "/admin/requests/" + no,
                    note == null ? "" : note, by, null, k.key() + "|" + doc);
        }
        Kind k = KINDS.get(in ? "in" : "off");
        StockDocument d = x.getDocument();
        if (d != null) {
            String oneC = d.getOneCDocumentNumber() != null ? " · 1С № " + d.getOneCDocumentNumber().trim().replaceFirst("^0+-0*(?=\\d)", "")
                    : d.getOneCSyncError() != null ? " · не ушло в 1С" : " · 1С: в очереди";
            String doc = (in ? "Поступление" : "Списание") + " товара № " + d.getId();
            return new Move(x.getCreatedAt(), x.getProduct(), k, x.getQuantity(), doc, null,
                    (d.getReason() == null ? "" : d.getReason()) + oneC, by, x.getId(), k.key() + "|" + doc);
        }
        String doc = in ? "Поступление" : "Списание";
        return new Move(x.getCreatedAt(), x.getProduct(), k, x.getQuantity(), doc, null, reason, by,
                x.getOneCSyncedAt() == null ? x.getId() : null, k.key() + "|" + doc + "|" + reason);
    }

    public static List<Move> shipped(Order o, Function<String, String> who) {
        List<Move> out = new ArrayList<>();
        String by = o.getShippedByName() != null && !o.getShippedByName().isBlank() ? o.getShippedByName() : "—";
        for (OrderItem i : o.getItems()) {
            out.add(new Move(o.getShippedAt(), i.getProduct(), KINDS.get("ship"), nz(i.getQuantity()), "Заказ № " + o.getId(),
                    "/admin/orders/" + o.getId(), o.getClient() == null ? "" : o.getClient().getName(), by, null, "ship|" + o.getId()));
        }
        return out;
    }

    /** Одна строка на документ (заказ, заявка, документ): «6 позиций» раскрываются. */
    public record Group(String key, Move first, List<Move> items) {
        public boolean single() {
            return items.size() == 1;
        }

        public String count() {
            return F.count(items.size(), "позиция", "позиции", "позиций");
        }

        public String qty() {
            BigDecimal s = items.stream().map(Move::q).reduce(BigDecimal.ZERO, BigDecimal::add);
            return (first.plus() ? "+" : "−") + F.num(s) + NB + "шт";
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
            LocalDate d = m.at().toLocalDate();
            cnt.merge(d, 1, Integer::sum);
            String k = m.at().withSecond(0).withNano(0) + "|" + m.groupKey();
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

    public static boolean matches(Move m, String[] words) {
        if (words.length == 0) return true;
        String hay = AdminRequests.norm(m.p().getName() + " " + m.doc() + " " + m.sub());
        for (String w : words) if (!hay.contains(w.replaceFirst("^№", ""))) return false;
        return true;
    }

    // ================================================================== остатки

    public record Today(BigDecimal in, BigDecimal out) {
    }

    public record Last(LocalDate day, String requestNo) {
    }

    /** Строка остатков. state: short — не хватит на заказы, res — в заказах, ok — есть, zero — нет на складе. */
    public record Row(Product p, BigDecimal stock, Res res, BigDecimal free, String state, Work work, Today today, Last last,
                      BigDecimal make) {
        public BigDecimal rq() {
            return res == null ? BigDecimal.ZERO : res.q();
        }

        public boolean covered() {
            return state.equals("short") && work != null && work.q().compareTo(free.negate()) >= 0;
        }

        public String boxes() {
            if (stock.signum() <= 0) return "нет на складе";
            BigDecimal box = AdminOrders.boxUnits(p);
            return box != null && box.compareTo(BigDecimal.ONE) > 0 ? F.boxText(stock, box) : "";
        }

        public BigDecimal value() {
            return stock.signum() > 0 && p.getPrice() != null ? stock.multiply(p.getPrice()) : null;
        }

        public String group() {
            String g = p.getGroup() != null && !p.getGroup().isBlank() ? p.getGroup() : p.getCategory() != null ? p.getCategory() : "";
            return g + (!p.isActive() ? (g.isEmpty() ? "" : " · ") + "в архиве" : "");
        }
    }

    static int rank(String state) {
        return switch (state) {
            case "short" -> 0;
            case "res" -> 1;
            case "ok" -> 2;
            default -> 3;
        };
    }

    /**
     * Целыми полузамесами, чтобы закрыть нехватку (как в макете): ⌈не хватает / выход × 2⌉ / 2, не меньше одного.
     */
    public static BigDecimal makeBatches(BigDecimal lack, Recipe rc) {
        if (rc == null || rc.getYieldQuantity() == null || rc.getYieldQuantity().signum() <= 0 || lack.signum() <= 0) return null;
        BigDecimal halves = lack.multiply(BigDecimal.valueOf(2)).divide(rc.getYieldQuantity(), 0, RoundingMode.CEILING);
        return halves.divide(BigDecimal.valueOf(2)).max(BigDecimal.ONE).stripTrailingZeros();
    }

    public static List<Row> rows(List<Product> products, Map<Long, Res> res, Map<Long, Work> work, List<Move> todayMoves,
                                 Map<Long, Last> last, Map<Long, Recipe> recipes) {
        Map<Long, BigDecimal[]> td = new HashMap<>();
        for (Move m : todayMoves) {
            BigDecimal[] a = td.computeIfAbsent(m.p().getId(), k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            if (m.plus()) a[0] = a[0].add(m.q());
            else a[1] = a[1].add(m.q());
        }
        List<Row> out = new ArrayList<>();
        for (Product p : products) {
            if (p.isSemiFinished()) continue;
            BigDecimal stock = nz(p.getQuantityInStock());
            Res r = res.get(p.getId());
            BigDecimal rq = r == null ? BigDecimal.ZERO : r.q();
            if (!p.isActive() && stock.signum() == 0 && rq.signum() == 0) continue;
            BigDecimal free = stock.subtract(rq);
            String state = free.signum() < 0 ? "short" : stock.signum() <= 0 ? "zero" : rq.signum() > 0 ? "res" : "ok";
            BigDecimal[] t = td.get(p.getId());
            out.add(new Row(p, stock, r, free, state, work.get(p.getId()), t == null ? null : new Today(t[0], t[1]), last.get(p.getId()),
                    state.equals("short") ? makeBatches(free.negate(), recipes.get(p.getId())) : null));
        }
        return out;
    }

    public static void sort(List<Row> rows, String sort) {
        Comparator<Row> byName = Comparator.comparing(x -> x.p().getName(), String.CASE_INSENSITIVE_ORDER);
        rows.sort(switch (sort) {
            case "qd" -> Comparator.comparing(Row::stock).reversed().thenComparing(byName);
            case "qa" -> Comparator.comparing(Row::stock).thenComparing(byName);
            case "name" -> byName;
            default -> Comparator.comparingInt((Row x) -> rank(x.state())).thenComparing(x -> x.state().equals("short") ? x.free() : BigDecimal.ZERO)
                    .thenComparing(byName);
        });
    }

    // ================================================================== документ

    public static final List<String> IN_WHY = List.of("Излишек при инвентаризации", "Вернули из магазина целым", "Перемещение с другого склада", "Другое");
    public static final List<String> OFF_WHY = List.of("Брак", "Истёк срок годности", "Порча при хранении", "Недостача при инвентаризации", "Дегустация, образцы", "Другое");

    public static Map<String, Object> docData(List<Row> rows, boolean canIn, boolean canOff) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("today", LocalDate.now().toString());
        m.put("items", rows.stream().filter(r -> r.p().isActive()).sorted(Comparator.comparing(r -> r.p().getName(), String.CASE_INSENSITIVE_ORDER))
                .map(r -> {
                    Map<String, Object> x = new LinkedHashMap<>();
                    x.put("id", r.p().getId());
                    x.put("name", r.p().getName());
                    x.put("unit", "шт");
                    x.put("stock", r.stock());
                    x.put("res", r.rq());
                    return x;
                }).toList());
        m.put("inWhy", IN_WHY);
        m.put("offWhy", OFF_WHY);
        m.put("canIn", canIn);
        m.put("canOff", canOff);
        m.put("url", "/admin/stock/doc");
        m.put("int", true);
        m.put("inTitle", "Поступление товара");
        m.put("offTitle", "Списание товара");
        m.put("noun", "Товар");
        m.put("inHint", "Выпуск цеха сюда не вносят — его оприходуют по заявке на сырьё. Здесь — приход без заявки: излишек, перемещение.");
        m.put("offHint", "Уходит со склада сразу; больше, чем есть на складе, списать нельзя. Документ уйдёт в 1С и проведётся.");
        return m;
    }
}
