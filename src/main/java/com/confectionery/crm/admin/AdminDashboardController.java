package com.confectionery.crm.admin;

import com.confectionery.crm.domain.*;
import com.confectionery.crm.repository.OrderRepository;
import com.confectionery.crm.repository.ProductRepository;
import com.confectionery.crm.repository.ProductReturnRepository;
import com.confectionery.crm.service.CurrentUserService;
import com.confectionery.crm.service.IngredientService;
import com.confectionery.crm.service.PickupSlotService;
import com.confectionery.crm.service.ProductionRequestService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Дашборд за любой день (серая дата в шапке — календарь: год назад и 30 дней вперёд): четыре плитки
 * («Выдача» дня, «Неоплаченные счета» — сейчас, «Выручка за 7 дней» по выбранный день, «Продажи» дня или
 * «К выдаче» для будущего дня), «Выдача» — часовые окна рабочего дня 7:00–18:00: заказы с сайта, когда клиент
 * заберёт, и наши действия (принят / собран / отгружен / завершён — когда и кто), «Требуют внимания» — сейчас,
 * «Выручка» и «Что берут» — за 7, 14 или 30 дней по выбранный день. Выручка — по дню выдачи заказа, за вычетом
 * возвратов по этому заказу; возвраты в штуках — по дню возврата.
 */
@Controller
@RequiredArgsConstructor
public class AdminDashboardController {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final ProductReturnRepository productReturnRepository;
    private final IngredientService ingredientService;
    private final ProductionRequestService productionRequestService;
    private final CurrentUserService currentUserService;
    private final PickupSlotService pickupSlotService;
    private final AdminFormat fmt;

    @GetMapping({"/admin", "/admin/"})
    public String home() {
        Role role = currentUserService.role().orElse(Role.ADMIN);
        return "redirect:" + AdminSections.home(role).href();
    }

    public record Kpi(int count, String sub) {
    }

    /** Рабочий день в окнах «Выдачи»: всё, что раньше 7:00, — в окне 7–8, всё, что позже 18:00, — в окне 17–18. */
    static final int DAY_FROM = 7, DAY_TO = 18;

    /**
     * Отметка в окне «Выдачи»: kind — order (заказ с сайта), pickup (клиент заберёт), accepted / collected /
     * shipped / completed (наши действия); time — настоящее время, под ним — кто.
     */
    public record Ev(String kind, String icon, String label, AdminOrders.Row row, LocalDateTime at, String time, String by) {
    }

    /** Часовое окно «Выдачи». */
    public record Win(int hour, String label, List<Ev> evs, int pickups, boolean past, boolean now) {
    }

    public record Attention(String dot, String icon, String title, String text, String href, String button) {
    }

    public record Top(Product product, BigDecimal pcs, double share) {
    }

    /** День выручки: выдано заказов на сумму (за вычетом их возвратов), возвраты этого дня — штук и на сумму. */
    public record DayRev(LocalDate d, BigDecimal sum, int n, BigDecimal rpcs, BigDecimal rsum) {
    }

    /** Период «Выручки» и «Что берут»: 7, 14 (по умолчанию) или 30 дней по выбранный день. */
    public record Per(int days, String href, boolean on) {
    }

    static final int[] PERIODS = {7, 14, 30};

    @GetMapping("/admin/dashboard")
    @Transactional(readOnly = true)
    public String dashboard(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
                            @RequestParam(defaultValue = "14") int p, Model model) {
        LocalDate today = LocalDate.now();
        LocalDate shown = day == null || day.isBefore(today.minusYears(1)) || day.isAfter(today.plusDays(30)) ? today : day;
        int period = p == 7 || p == 30 ? p : 14;
        LocalDate end = shown.isAfter(today) ? today : shown;          // выручка и «Что берут» — по выбранный день, будущий — по сегодня
        String lbl = dayLabel(shown, today);
        Role role = currentUserService.role().orElse(Role.ADMIN);
        model.addAttribute("title", "Дашборд");
        model.addAttribute("sub", fmt.dayTitle(shown));
        model.addAttribute("subPick", shown.toString());
        model.addAttribute("subKeep", period == 14 ? null : "p=" + period);
        model.addAttribute("subBase", "/admin/dashboard");
        model.addAttribute("subParam", "day");
        model.addAttribute("sec", "dashboard");
        model.addAttribute("dayLbl", lbl);
        model.addAttribute("isToday", shown.equals(today));
        model.addAttribute("isFuture", shown.isAfter(today));

        // 1. Выдача дня: сколько заказов, штук и коробок, в каких они статусах
        List<AdminOrders.Row> dayRows = rowsOfDay(shown);
        Map<String, Integer> by = new HashMap<>();
        int boxes = 0;
        BigDecimal pcs = BigDecimal.ZERO, plan = BigDecimal.ZERO;
        for (AdminOrders.Row r : dayRows) {
            by.merge(r.badge(), 1, Integer::sum);
            boxes += r.totals().boxes();
            pcs = pcs.add(r.totals().packs());
            plan = plan.add(r.totals().net());
        }
        List<String> parts = new ArrayList<>();
        String[][] names = {{"done", "выдан", "выдано", "выдано"}, {"ready", "готов", "готовы", "готовы"},
                {"work", "собирается", "собираются", "собираются"}, {"new", "принят", "приняты", "приняты"},
                {"sent", "новый", "новых", "новых"}, {"noshow", "не забран", "не забраны", "не забраны"}};
        for (String[] x : names) {
            Integer n = by.get(x[0]);
            if (n != null) parts.add(n + AdminFormat.NB + fmt.plural(n, x[1], x[2], x[3]));
        }
        model.addAttribute("kToday", new Kpi(dayRows.size(), dayRows.isEmpty()
                ? (shown.isAfter(today) ? "заказов на этот день пока нет" : "выдач не было")
                : fmt.num(pcs) + AdminFormat.NB + "шт · " + boxes + AdminFormat.NB + "кор. · " + String.join(" · ", parts)));
        model.addAttribute("dayHref", shown.equals(today) ? "/admin/orders?date=today"
                : shown.equals(today.plusDays(1)) ? "/admin/orders?date=tomorrow" : null);

        // 2. Неоплаченные счета — сейчас (новые с сайта — в «Требуют внимания»)
        List<Order> unpaid = orderRepository.findByStatusIn(EnumSet.complementOf(EnumSet.of(OrderStatus.CANCELLED,
                        OrderStatus.PLACED, OrderStatus.COMPLETED))).stream()
                .filter(AdminOrders::unpaid).sorted(Comparator.comparing(Order::getId)).toList();
        BigDecimal unpaidSum = unpaid.stream().map(o -> AdminOrders.totals(o).net()).reduce(BigDecimal.ZERO, BigDecimal::add);
        model.addAttribute("unpaidN", unpaid.size());
        model.addAttribute("unpaidSum", unpaidSum);
        model.addAttribute("unpaidNos", unpaid.stream().limit(12).map(o -> String.valueOf(o.getId()))
                .reduce((a, b) -> a + ", " + b).orElse("") + (unpaid.size() > 12 ? " …" : ""));

        List<Order> sent = orderRepository.findByStatusOrderByCreatedAtDesc(OrderStatus.PLACED).stream()
                .sorted(Comparator.comparing(Order::getId)).toList();
        model.addAttribute("sent", sent);
        model.addAttribute("sentNos", sent.stream().map(o -> String.valueOf(o.getId())).reduce((a, b) -> a + ", " + b).orElse(""));

        // выручка по дням — одним разом на всё, что нужно: 7 + 7 дней для плитки, период, день до выбранного
        LocalDate from = end.minusDays(period - 1);
        LocalDate need = Collections.min(List.of(from, end.minusDays(13), shown.minusDays(1)));
        Map<LocalDate, DayRev> rev = revDays(need, end);

        // 3. Выручка за 7 дней по выбранный день и сравнение с 7 днями до них
        BigDecimal w1 = BigDecimal.ZERO, w0 = BigDecimal.ZERO;
        int n1 = 0;
        for (LocalDate d = end.minusDays(13); !d.isAfter(end); d = d.plusDays(1)) {
            DayRev x = rev.get(d);
            if (d.isAfter(end.minusDays(7))) {
                w1 = w1.add(x.sum());
                n1 += x.n();
            } else {
                w0 = w0.add(x.sum());
            }
        }
        int delta = w0.signum() == 0 ? 0 : w1.subtract(w0).multiply(BigDecimal.valueOf(100)).divide(w0, 0, RoundingMode.HALF_UP).intValue();
        model.addAttribute("rev7", w1);
        model.addAttribute("rev7n", n1);
        model.addAttribute("revDelta", delta);
        model.addAttribute("rev7End", end.equals(today) ? null : end);

        // 4. Продажи дня (выданные в этот день заказы за вычетом их возвратов) и день до него; будущий день — к выдаче
        DayRev dd = rev.get(end);
        model.addAttribute("salesToday", shown.isAfter(today) ? plan : dd.sum());
        model.addAttribute("salesTodayN", dd.n());
        model.addAttribute("salesRet", shown.isAfter(today) ? BigDecimal.ZERO : dd.rpcs());
        model.addAttribute("salesYesterday", rev.get(shown.minusDays(1)) == null ? BigDecimal.ZERO : rev.get(shown.minusDays(1)).sum());
        model.addAttribute("prevLbl", dayLabel(shown.minusDays(1), today));
        model.addAttribute("planN", dayRows.size());

        // Выручка: период по выбранный день
        List<DayRev> days = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO, rpcs = BigDecimal.ZERO, rsum = BigDecimal.ZERO;
        int nTotal = 0;
        List<Map<String, Object>> chart = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(end); d = d.plusDays(1)) {
            DayRev x = rev.get(d);
            days.add(x);
            total = total.add(x.sum());
            nTotal += x.n();
            rpcs = rpcs.add(x.rpcs());
            rsum = rsum.add(x.rsum());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("d", d.toString());
            m.put("sum", x.sum().setScale(0, RoundingMode.HALF_UP));
            m.put("n", x.n());
            m.put("rpcs", x.rpcs().stripTrailingZeros().toPlainString());
            chart.add(m);
        }
        Map<String, Object> revData = new LinkedHashMap<>();
        revData.put("days", chart);
        revData.put("sel", shown.toString());
        revData.put("today", today.toString());
        revData.put("p", period);
        model.addAttribute("revJson", AdminJson.of(revData));
        model.addAttribute("revTotal", total);
        model.addAttribute("revCount", nTotal);
        model.addAttribute("revRetPcs", rpcs);
        model.addAttribute("revRetSum", rsum);
        model.addAttribute("revFrom", from);
        model.addAttribute("revEnd", end);
        model.addAttribute("period", period);
        List<DayRev> table = new ArrayList<>(days);
        Collections.reverse(table);
        model.addAttribute("revTable", table);
        List<Per> pers = new ArrayList<>();
        for (int x : PERIODS) pers.add(new Per(x, link(shown, today, x), x == period));
        model.addAttribute("periods", pers);

        // Не забрали — в «Требуют внимания»
        List<Order> noShow = orderRepository.findByStatusOrderByCreatedAtDesc(OrderStatus.NO_SHOW).stream()
                .sorted(Comparator.comparing(AdminOrders::day).thenComparing(Order::getId)).toList();
        model.addAttribute("noShow", noShow);

        // Выдача: окна 7:00–18:00 выбранного дня
        model.addAttribute("wins", windows(shown, today));
        model.addAttribute("capacity", pickupSlotService.capacity());
        model.addAttribute("day", shown);
        model.addAttribute("dayMin", today.minusYears(1).toString());
        model.addAttribute("dayMax", today.plusDays(30).toString());

        // Требуют внимания — сейчас, какой бы день ни был выбран
        List<Attention> att = new ArrayList<>();
        if (!sent.isEmpty()) {
            att.add(new Attention("d-cream", "s-sent",
                    sent.size() + AdminFormat.NB + fmt.plural(sent.size(), "новый заказ", "новых заказа", "новых заказов") + " с сайта",
                    "ждут подтверждения: № " + model.getAttribute("sentNos"), "/admin/orders?status=sent", "Открыть"));
        }
        for (Order o : noShow) {
            AdminOrders.Totals t = AdminOrders.totals(o);
            att.add(new Attention("d-crim", "s-noshow", "№ " + o.getId() + " не забрали",
                    o.getClient().getName() + " · окно " + fmt.relDay(AdminOrders.day(o))
                            + (o.getPickupHour() != null ? " " + fmt.win(o.getPickupHour()) : "")
                            + " · " + t.boxes() + AdminFormat.NB + "кор."
                            + (o.getClient().getPhone() != null ? " · " + fmt.phone(o.getClient().getPhone()) : ""),
                    "/admin/orders/" + o.getId(), "Открыть"));
        }
        // долги со сроком «долг до …»: срок прошёл или сегодня
        boolean seesClients = AdminSections.byKey("clients").visibleTo(role);
        for (Order o : orderRepository.findUnpaidDebts()) {
            if (o.isPaid() || o.getDebtDueDate().isAfter(today)) continue;
            long late = java.time.temporal.ChronoUnit.DAYS.between(o.getDebtDueDate(), today);
            Client c = o.getClient();
            att.add(new Attention(late > 0 ? "d-crim" : "d-yolk", "cash",
                    (late > 0 ? "Просрочен долг: " : "Сегодня срок долга: ") + c.getName(),
                    fmt.money(AdminOrders.totals(o).pay()) + " по заказу № " + o.getId()
                            + (late > 0 ? " · срок был " + fmt.dm(o.getDebtDueDate()) + ", " + fmt.count(late, "день", "дня", "дней") + " назад" : "")
                            + (c.getPhone() != null ? " · " + fmt.phone(c.getPhone()) : ""),
                    seesClients ? "/admin/clients/" + c.getId() : "/admin/orders/" + o.getId(), seesClients ? "Клиент" : "Открыть"));
        }
        for (AdminOrders.Row r : rowsOfDay(today)) {
            Order o = r.order();
            if ((o.getStatus() == OrderStatus.READY || o.getStatus() == OrderStatus.NEW) && !o.isPaid()
                    && o.getPaymentMethod() == PaymentMethod.INVOICE) {
                att.add(new Attention("d-yolk", "cash", "№ " + o.getId() + " не оплачен",
                        "по счёту · " + o.getClient().getName() + " · выдача сегодня"
                                + (o.getPickupHour() != null ? " " + fmt.win(o.getPickupHour()) : ""),
                        "/admin/orders/" + o.getId(), "Открыть"));
            }
        }
        if (AdminSections.byKey("requests").visibleTo(role)) {
            for (ProductionRequest pr : productionRequestService.dashboardSummary(today.atStartOfDay(),
                    today.plusDays(1).atStartOfDay(), 5).pendingRequests()) {
                String lines = pr.getItems().stream().limit(4)
                        .map(i -> i.getIngredient().getName() + " " + fmt.num(i.getRequiredQuantity()) + AdminFormat.NB
                                + i.getIngredient().getUnit())
                        .reduce((a, b) -> a + ", " + b).orElse("");
                att.add(new Attention("d-paper", "requests", "Заявка на сырьё № " + pr.getId() + " ждёт сборки",
                        pr.getCreatedBy() + (lines.isEmpty() ? "" : ": " + lines), "/admin/requests/" + pr.getId(), "Заявки"));
            }
        }
        if (AdminSections.byKey("materials").visibleTo(role)) {
            List<Ingredient> low = ingredientService.findLowStock();
            if (!low.isEmpty()) {
                att.add(new Attention("d-paper", "materials", "Сырьё на исходе: " + low.size() + AdminFormat.NB
                        + fmt.plural(low.size(), "позиция", "позиции", "позиций"),
                        low.stream().limit(5).map(m -> m.getName() + " — " + fmt.dec(m.getQuantityInStock(), 0) + AdminFormat.NB + m.getUnit())
                                .reduce((a, b) -> a + " · " + b).orElse("") + (low.size() > 5 ? " · …" : ""),
                        "/admin/materials?seg=low", "Склад"));
            }
        }
        model.addAttribute("attention", att);

        // Что берут: штук за период по выбранный день, по выданным заказам — в тех же единицах, что строки заказа
        List<Top> top = new ArrayList<>();
        List<Object[]> sold = new ArrayList<>(orderRepository.soldByProduct(from.atStartOfDay(), end.plusDays(1).atStartOfDay()));
        sold.sort((a, b) -> ((BigDecimal) b[1]).compareTo((BigDecimal) a[1]));
        Map<Long, Product> products = new HashMap<>();
        productRepository.findAllById(sold.stream().map(r -> (Long) r[0]).toList()).forEach(x -> products.put(x.getId(), x));
        BigDecimal max = sold.isEmpty() ? BigDecimal.ONE : ((BigDecimal) sold.get(0)[1]).max(BigDecimal.ONE);
        for (Object[] r : sold) {
            Product x = products.get((Long) r[0]);
            if (x == null) continue;
            BigDecimal q = (BigDecimal) r[1];
            top.add(new Top(x, q, q.doubleValue() / max.doubleValue() * 100));
            if (top.size() == 7) break;
        }
        model.addAttribute("top", top);
        return "admin/dashboard";
    }

    /** сегодня / вчера / завтра / 10 окт */
    String dayLabel(LocalDate d, LocalDate today) {
        return d.equals(today) ? "сегодня" : d.equals(today.minusDays(1)) ? "вчера" : d.equals(today.plusDays(1)) ? "завтра" : fmt.dm(d);
    }

    /** Ссылка на дашборд: день (без него — сегодня) и период (без него — 14 дней). */
    static String link(LocalDate d, LocalDate today, int period) {
        List<String> q = new ArrayList<>();
        if (!d.equals(today)) q.add("day=" + d);
        if (period != 14) q.add("p=" + period);
        return "/admin/dashboard" + (q.isEmpty() ? "" : "?" + String.join("&", q));
    }

    /**
     * Выручка по дням с {@code from} по {@code to}: выданные заказы по дню выдачи, за вычетом возвратов по этим
     * заказам; возвраты дня — штук и на сумму по дню возврата. Дни без выдач — нули.
     */
    Map<LocalDate, DayRev> revDays(LocalDate from, LocalDate to) {
        LocalDateTime start = from.atStartOfDay(), stop = to.plusDays(1).atStartOfDay();
        Map<Long, BigDecimal> ret = new HashMap<>();
        for (Object[] r : productReturnRepository.sumByOrderShippedBetween(start, stop)) ret.put((Long) r[0], (BigDecimal) r[1]);
        Map<LocalDate, BigDecimal> sum = new HashMap<>();
        Map<LocalDate, Integer> cnt = new HashMap<>();
        for (Object[] r : orderRepository.revenueByOrder(start, stop)) {
            LocalDate d = ((LocalDateTime) r[1]).toLocalDate();
            sum.merge(d, ((BigDecimal) r[2]).subtract(ret.getOrDefault((Long) r[0], BigDecimal.ZERO)), BigDecimal::add);
            cnt.merge(d, 1, Integer::sum);
        }
        Map<LocalDate, BigDecimal[]> rd = new HashMap<>();
        for (Object[] r : productReturnRepository.sumByDayBetween(from, to)) {
            rd.put((LocalDate) r[0], new BigDecimal[]{(BigDecimal) r[1], (BigDecimal) r[2]});
        }
        Map<LocalDate, DayRev> out = new LinkedHashMap<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            BigDecimal[] x = rd.getOrDefault(d, new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            out.put(d, new DayRev(d, sum.getOrDefault(d, BigDecimal.ZERO).max(BigDecimal.ZERO), cnt.getOrDefault(d, 0), x[0], x[1]));
        }
        return out;
    }

    /**
     * Окна «Выдачи» за день. Заказ с сайта — по времени оформления: до 7:00 — в окно 7–8, после 18:00 — на
     * утро следующего дня. Наши действия — по времени отметки: до 7:00 — в окно 7–8, после 18:00 — в окно 17–18
     * того же дня. «Заберёт» — окно, которое выбрал клиент.
     */
    private List<Win> windows(LocalDate d, LocalDate today) {
        Map<Long, Order> orders = new LinkedHashMap<>();
        for (Order o : orderRepository.findForPickupDay(d, d.atStartOfDay(), d.plusDays(1).atStartOfDay())) orders.put(o.getId(), o);
        for (Order o : orderRepository.findWithActivity(d.minusDays(1).atTime(DAY_TO, 0), d.atStartOfDay(), d.plusDays(1).atStartOfDay())) {
            orders.putIfAbsent(o.getId(), o);
        }
        Map<Integer, List<Ev>> by = new TreeMap<>();
        Map<Integer, Integer> pickups = new HashMap<>();
        for (Order o : orders.values()) {
            if (o.getStatus() == OrderStatus.CANCELLED) continue;
            AdminOrders.Row row = AdminOrders.row(o);
            boolean site = AdminOrders.byClient(o);
            if (site && o.getCreatedAt() != null) {
                LocalDateTime t = o.getCreatedAt();
                boolean late = t.getHour() >= DAY_TO;
                if ((late ? t.toLocalDate().plusDays(1) : t.toLocalDate()).equals(d)) {
                    put(by, late ? DAY_FROM : clamp(t.getHour()), new Ev("order", "s-sent", "Заказ с сайта", row, t, fmt.at(t, d), null));
                }
            }
            if (o.getPickupHour() != null && AdminOrders.day(o).equals(d)) {
                int h = clamp(o.getPickupHour());
                put(by, h, new Ev("pickup", "truck", "Заберёт", row, d.atTime(h, 0), fmt.win(o.getPickupHour()), null));
                pickups.merge(h, 1, Integer::sum);
            }
            act(by, d, "accepted", "s-new", "Принят", row, AdminOrders.accepted(o));
            act(by, d, "collected", "s-ready", "Собран", row, AdminOrders.collected(o));
            act(by, d, "shipped", "s-done", "Отгружен", row, AdminOrders.shipped(o));
            act(by, d, "completed", "cash", "Завершён", row, AdminOrders.completed(o));
        }
        LocalDateTime now = LocalDateTime.now();
        boolean isToday = d.equals(today);
        List<Win> out = new ArrayList<>();
        for (int h = DAY_FROM; h < DAY_TO; h++) {
            List<Ev> evs = by.getOrDefault(h, List.of()).stream()
                    .sorted(Comparator.comparing((Ev e) -> e.kind().equals("pickup") ? 0 : 1).thenComparing(Ev::at)
                            .thenComparing(e -> e.row().id()))
                    .toList();
            int nowH = now.getHour();
            boolean cur = isToday && h == nowH;
            boolean past = d.isBefore(today) || isToday && h < nowH;
            out.add(new Win(h, fmt.win(h), evs, pickups.getOrDefault(h, 0), past, cur));
        }
        return out;
    }

    private void act(Map<Integer, List<Ev>> by, LocalDate d, String kind, String icon, String label,
                     AdminOrders.Row row, AdminOrders.Mark m) {
        if (m == null || m.at() == null || !m.at().toLocalDate().equals(d)) return;
        put(by, clamp(m.at().getHour()), new Ev(kind, icon, label, row, m.at(), fmt.time(m.at()), m.by()));
    }

    private static void put(Map<Integer, List<Ev>> by, int hour, Ev e) {
        by.computeIfAbsent(hour, k -> new ArrayList<>()).add(e);
    }

    private static int clamp(int hour) {
        return Math.max(DAY_FROM, Math.min(DAY_TO - 1, hour));
    }

    private List<AdminOrders.Row> rowsOfDay(LocalDate day) {
        return orderRepository.findForPickupDay(day, day.atStartOfDay(), day.plusDays(1).atStartOfDay()).stream()
                .map(AdminOrders::row).toList();
    }
}
