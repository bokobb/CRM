package com.confectionery.crm.admin;

import com.confectionery.crm.domain.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Клиент в админке, как в макете (cStats, cBuys, usualLines): сколько выдано и на сколько, как часто заказывает,
 * долги, возвраты в штуках, что берёт и «обычный заказ», статус — «Долг просрочен», «Пора напомнить», «Новый»,
 * «Заказывает». Долг — выданные и не оплаченные заказы; просрочен — только тот, у которого бухгалтер поставил срок
 * («Долг до …») и срок прошёл.
 */
public final class AdminClients {

    private AdminClients() {
    }

    public static final Map<String, String> ST = Map.of("debt", "Долг просрочен", "remind", "Пора напомнить", "new", "Новый", "active", "Заказывает");
    static final Map<String, Integer> ST_ORDER = Map.of("debt", 0, "remind", 1, "new", 2, "active", 3);

    /** Выданный и не оплаченный заказ: к оплате, когда выдан, срок (долг до … или день выдачи), дней после срока. */
    public record Debt(Order order, BigDecimal sum, LocalDate shipped, LocalDate due, boolean explicit, long late) {
        public boolean overdue() {
            return explicit && late > 0;
        }

        public boolean dueToday() {
            return explicit && late == 0;
        }
    }

    /** Что клиент берёт: товар, штук всего, в скольких заказах, обычно штук за раз, входит ли в «обычный заказ». */
    public record Buy(Product product, BigDecimal pcs, int n, BigDecimal median, boolean usual, double bar) {
    }

    public record Stats(Client client, List<Order> orders, List<Order> done, List<Order> active, int n, LocalDate last,
                        Integer interval, LocalDate since, BigDecimal sum, BigDecimal sum30, BigDecimal avg, BigDecimal avgBoxes,
                        List<Debt> debts, BigDecimal debt, BigDecimal lateSum, Long late, LocalDate due, Long gap, LocalDate next,
                        String st) {
        public String stLabel() {
            return ST.get(st);
        }

        public boolean hasOverdue() {
            return late != null && late > 0;
        }
    }

    public static LocalDate doneDay(Order o) {
        LocalDateTime t = AdminOrders.doneAt(o);
        return t != null ? t.toLocalDate() : AdminOrders.day(o);
    }

    static long days(LocalDate a, LocalDate b) {
        return ChronoUnit.DAYS.between(a, b);
    }

    static BigDecimal median(List<BigDecimal> v) {
        if (v.isEmpty()) return null;
        List<BigDecimal> s = new ArrayList<>(v);
        Collections.sort(s);
        int m = s.size() / 2;
        return s.size() % 2 == 1 ? s.get(m) : s.get(m - 1).add(s.get(m)).divide(BigDecimal.valueOf(2), 3, RoundingMode.HALF_UP);
    }

    /** Заказ ещё в работе: новая, принят, собирается, готов, не забрали. */
    static boolean active(Order o) {
        return switch (o.getStatus().badge()) {
            case "sent", "new", "work", "ready", "noshow" -> true;
            default -> false;
        };
    }

    public static Stats stats(Client c, List<Order> all, LocalDate today) {
        List<Order> orders = new ArrayList<>(all);
        orders.sort(Comparator.comparing(Order::getId).reversed());
        List<Order> done = orders.stream().filter(o -> o.getStatus().isShipped())
                .sorted(Comparator.comparing(AdminClients::doneDay).thenComparing(Order::getId)).toList();
        List<Order> act = orders.stream().filter(AdminClients::active)
                .sorted(Comparator.comparing(AdminOrders::day).thenComparing(o -> o.getPickupHour() == null ? 99 : o.getPickupHour())
                        .thenComparing(Order::getId)).toList();
        List<BigDecimal> gaps = new ArrayList<>();
        for (int i = 1; i < done.size(); i++) gaps.add(BigDecimal.valueOf(days(doneDay(done.get(i - 1)), doneDay(done.get(i)))));
        BigDecimal med = median(gaps);
        Integer interval = med == null ? null : Math.max(1, med.setScale(0, RoundingMode.HALF_UP).intValue());
        LocalDate last = done.isEmpty() ? null : doneDay(done.get(done.size() - 1));
        LocalDate since = c.getCreatedAt() == null ? null : c.getCreatedAt().toLocalDate();
        for (Order o : orders) {
            if (o.getCreatedAt() == null) continue;
            LocalDate d = o.getCreatedAt().toLocalDate();
            if (since == null || d.isBefore(since)) since = d;
        }
        BigDecimal sum = BigDecimal.ZERO, sum30 = BigDecimal.ZERO, boxes = BigDecimal.ZERO;
        LocalDate from30 = today.minusDays(29);
        List<Debt> debts = new ArrayList<>();
        for (Order o : done) {
            AdminOrders.Totals t = AdminOrders.totals(o);
            sum = sum.add(t.net());
            boxes = boxes.add(BigDecimal.valueOf(t.boxes()));
            LocalDate dd = doneDay(o);
            if (!dd.isBefore(from30)) sum30 = sum30.add(t.net());
            if (!o.isPaid()) {
                boolean explicit = o.getDebtDueDate() != null;
                LocalDate due = explicit ? o.getDebtDueDate() : dd;
                debts.add(new Debt(o, t.pay(), dd, due, explicit, days(due, today)));
            }
        }
        debts.sort(Comparator.comparing(Debt::due).thenComparing(d -> d.order().getId()));
        int n = done.size();
        BigDecimal avg = n == 0 ? BigDecimal.ZERO : sum.divide(BigDecimal.valueOf(n), 0, RoundingMode.HALF_UP);
        BigDecimal avgBoxes = n == 0 ? BigDecimal.ZERO : boxes.divide(BigDecimal.valueOf(n), 0, RoundingMode.HALF_UP);
        BigDecimal debt = BigDecimal.ZERO, lateSum = BigDecimal.ZERO;
        Long late = null;
        LocalDate due = null;
        for (Debt d : debts) {
            debt = debt.add(d.sum());
            if (!d.explicit()) continue;
            if (late == null || d.late() > late) late = d.late();
            if (due == null || d.due().isBefore(due)) due = d.due();
            if (d.late() > 0) lateSum = lateSum.add(d.sum());
        }
        Long gap = last == null ? null : days(last, today);
        LocalDate next = last != null && interval != null ? last.plusDays(interval) : null;
        String st;
        if (late != null && late > 0) st = "debt";
        else if (n == 0 && act.isEmpty()) st = since != null && days(since, today) > 30 ? "remind" : "new";
        else if (act.isEmpty() && gap != null && gap > Math.max(10, Math.round((interval != null ? interval : 14) * 1.5))) st = "remind";
        else if (n <= 1 && since != null && days(since, today) <= 14) st = "new";
        else st = "active";
        return new Stats(c, orders, done, act, n, last, interval, since, sum, sum30, avg, avgBoxes, debts, debt, lateSum, late, due, gap, next, st);
    }

    /** Что берёт: штук по выданным заказам, больше всего сверху; «обычный» — есть хотя бы в 40 % заказов (в каждом, если заказов меньше трёх). */
    public static List<Buy> buys(Stats s) {
        Map<Product, BigDecimal> pcs = new LinkedHashMap<>();
        Map<Product, List<BigDecimal>> qs = new HashMap<>();
        for (Order o : s.done()) {
            Map<Product, BigDecimal> inOrder = new LinkedHashMap<>();
            for (OrderItem i : o.getItems()) inOrder.merge(i.getProduct(), i.getQuantity(), BigDecimal::add);
            inOrder.forEach((p, q) -> {
                pcs.merge(p, q, BigDecimal::add);
                qs.computeIfAbsent(p, k -> new ArrayList<>()).add(q);
            });
        }
        int need = s.n() >= 3 ? (int) Math.ceil(s.n() * 0.4) : s.n();
        BigDecimal max = pcs.values().stream().max(Comparator.naturalOrder()).orElse(BigDecimal.ONE).max(BigDecimal.ONE);
        List<Buy> out = new ArrayList<>();
        for (Map.Entry<Product, BigDecimal> e : pcs.entrySet()) {
            int n = qs.get(e.getKey()).size();
            out.add(new Buy(e.getKey(), e.getValue(), n, median(qs.get(e.getKey())), s.n() > 0 && n >= need && e.getKey().isActive(),
                    e.getValue().doubleValue() / max.doubleValue() * 100));
        }
        out.sort(Comparator.comparing(Buy::pcs).reversed().thenComparing(Comparator.comparing(Buy::n).reversed()));
        return out;
    }

    /** «Обычный заказ»: товары из buys с пометкой usual, по обычному числу целых коробок (без коробки — обычное число штук). */
    public static List<Map<String, Object>> usual(Stats s) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Buy b : buys(s)) {
            if (!b.usual()) continue;
            BigDecimal box = AdminOrders.boxUnits(b.product());
            BigDecimal qty;
            if (box == null || box.signum() <= 0) qty = b.median().setScale(0, RoundingMode.HALF_UP).max(BigDecimal.ONE);
            else qty = b.median().divide(box, 0, RoundingMode.HALF_UP).max(BigDecimal.ONE).multiply(box);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", b.product().getId());
            m.put("qty", qty.stripTrailingZeros().toPlainString());
            rows.add(m);
        }
        return rows;
    }

    /** Цифры телефона для сравнения — последние 10 (как ClientService.findByPhone): «8 700 …», «+7 700 …» — один номер. */
    public static String phoneKey(String phone) {
        if (phone == null) return "";
        String d = phone.replaceAll("\\D", "");
        return d.length() >= 10 ? d.substring(d.length() - 10) : d;
    }

    /** Телефон в одном виде: «+7 700 000 00 11»; если цифр не 10/11 — как ввели. */
    public static String phoneFormat(String phone) {
        if (phone == null || phone.isBlank()) return null;
        String d = phone.replaceAll("\\D", "");
        if (d.length() == 11 && (d.charAt(0) == '8' || d.charAt(0) == '7')) d = "7" + d.substring(1);
        else if (d.length() == 10) d = "7" + d;
        else return phone.trim();
        return "+7 " + d.substring(1, 4) + " " + d.substring(4, 7) + " " + d.substring(7, 9) + " " + d.substring(9, 11);
    }

    /** Буква в кружке: первая буква названия в кавычках «…», иначе первая буква названия. */
    public static String initial(String name) {
        if (name == null || name.isBlank()) return "?";
        int q = name.indexOf('«');
        String s = q >= 0 && q + 1 < name.length() ? name.substring(q + 1).trim() : name.trim();
        return s.isEmpty() ? "?" : s.substring(0, 1).toUpperCase(Locale.ROOT);
    }

    /** Ключ сортировки по названию: то, что в кавычках, иначе всё название. */
    public static String nameKey(String name) {
        if (name == null) return "";
        int a = name.indexOf('«'), b = name.indexOf('»', a + 1);
        return (a >= 0 && b > a ? name.substring(a + 1, b) : name).toLowerCase(Locale.ROOT).replace('ё', 'е');
    }
}
