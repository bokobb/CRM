package com.confectionery.crm.admin;

import com.confectionery.crm.domain.*;
import com.confectionery.crm.repository.OrderRepository;
import com.confectionery.crm.repository.ProductRepository;
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
 * Дашборд: четыре плитки («Выдача сегодня», «Неоплаченные счета», «Выручка за 7 дней», «Продажи за день»),
 * «Выдача» — часовые окна рабочего дня 7:00–18:00: заказы с сайта, когда клиент заберёт, и наши действия
 * (принят / собран / отгружен / завершён — когда и кто), «Требуют внимания», «Выручка», «Что берут».
 */
@Controller
@RequiredArgsConstructor
public class AdminDashboardController {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
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

    public record Top(Product product, BigDecimal boxes, double share) {
    }

    @GetMapping("/admin/dashboard")
    @Transactional(readOnly = true)
    public String dashboard(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
                            Model model) {
        LocalDate today = LocalDate.now();
        LocalDate shown = day == null || day.isBefore(today) || day.isAfter(today.plusDays(2)) ? today : day;
        Role role = currentUserService.role().orElse(Role.ADMIN);
        model.addAttribute("title", "Дашборд");
        model.addAttribute("sub", fmt.dayTitle(today));
        model.addAttribute("sec", "dashboard");

        // 1. Выдача сегодня
        List<AdminOrders.Row> todayRows = rowsOfDay(today);
        Map<String, Integer> by = new HashMap<>();
        int boxes = 0;
        for (AdminOrders.Row r : todayRows) {
            by.merge(r.badge(), 1, Integer::sum);
            boxes += r.totals().boxes();
        }
        List<String> parts = new ArrayList<>();
        String[][] names = {{"done", "выдан", "выдано", "выдано"}, {"ready", "готов", "готовы", "готовы"},
                {"work", "собирается", "собираются", "собираются"}, {"new", "принят", "приняты", "приняты"},
                {"sent", "новый", "новых", "новых"}, {"noshow", "не забран", "не забраны", "не забраны"}};
        for (String[] p : names) {
            Integer n = by.get(p[0]);
            if (n != null) parts.add(n + AdminFormat.NB + fmt.plural(n, p[1], p[2], p[3]));
        }
        model.addAttribute("kToday", new Kpi(todayRows.size(),
                boxes + AdminFormat.NB + "кор. · " + (parts.isEmpty() ? "выдач нет" : String.join(" · ", parts))));

        // 2. Неоплаченные счета (новые с сайта — в «Требуют внимания»)
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

        // 3. Выручка: 14 дней по дню выдачи
        LocalDate from = today.minusDays(13);
        Map<LocalDate, BigDecimal> sum = new HashMap<>();
        Map<LocalDate, Integer> cnt = new HashMap<>();
        for (Object[] r : orderRepository.revenueByOrder(from.atStartOfDay(), today.plusDays(1).atStartOfDay())) {
            LocalDate d = ((LocalDateTime) r[1]).toLocalDate();
            sum.merge(d, (BigDecimal) r[2], BigDecimal::add);
            cnt.merge(d, 1, Integer::sum);
        }
        List<Map<String, Object>> days = new ArrayList<>();
        BigDecimal w1 = BigDecimal.ZERO, w0 = BigDecimal.ZERO, total = BigDecimal.ZERO;
        int n1 = 0, nTotal = 0;
        for (LocalDate d = from; !d.isAfter(today); d = d.plusDays(1)) {
            BigDecimal s = sum.getOrDefault(d, BigDecimal.ZERO);
            int n = cnt.getOrDefault(d, 0);
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("d", d.toString());
            x.put("sum", s.setScale(0, RoundingMode.HALF_UP));
            x.put("n", n);
            days.add(x);
            total = total.add(s);
            nTotal += n;
            if (d.isAfter(today.minusDays(7))) {
                w1 = w1.add(s);
                n1 += n;
            } else {
                w0 = w0.add(s);
            }
        }
        int delta = w0.signum() == 0 ? 0 : w1.subtract(w0).multiply(BigDecimal.valueOf(100)).divide(w0, 0, RoundingMode.HALF_UP).intValue();
        model.addAttribute("rev7", w1);
        model.addAttribute("rev7n", n1);
        model.addAttribute("revDelta", delta);
        model.addAttribute("revDays", days);
        model.addAttribute("revJson", AdminJson.of(days));
        model.addAttribute("revTotal", total);
        model.addAttribute("revCount", nTotal);
        model.addAttribute("revFrom", from);
        List<Map<String, Object>> table = new ArrayList<>(days);
        Collections.reverse(table);
        model.addAttribute("revTable", table);

        // 4. Продажи за день: выданные сегодня заказы (как в «Выручке»), сравнение со вчера
        model.addAttribute("salesToday", sum.getOrDefault(today, BigDecimal.ZERO));
        model.addAttribute("salesTodayN", cnt.getOrDefault(today, 0));
        model.addAttribute("salesYesterday", sum.getOrDefault(today.minusDays(1), BigDecimal.ZERO));

        // Не забрали — в «Требуют внимания»
        List<Order> noShow = orderRepository.findByStatusOrderByCreatedAtDesc(OrderStatus.NO_SHOW).stream()
                .sorted(Comparator.comparing(AdminOrders::day).thenComparing(Order::getId)).toList();
        model.addAttribute("noShow", noShow);

        // Выдача: окна 7:00–18:00 выбранного дня
        model.addAttribute("wins", windows(shown, today));
        model.addAttribute("capacity", pickupSlotService.capacity());
        model.addAttribute("day", shown);
        model.addAttribute("dayTabs", List.of(today, today.plusDays(1), today.plusDays(2)));

        // Требуют внимания
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
        for (AdminOrders.Row r : todayRows) {
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
                        "/admin/materials?low=1", "Склад"));
            }
        }
        model.addAttribute("attention", att);

        // Что берут: коробок за 14 дней по выданным заказам
        List<Top> top = new ArrayList<>();
        Map<Long, BigDecimal> qty = new HashMap<>();
        for (Object[] r : orderRepository.soldByProduct(from.atStartOfDay(), today.plusDays(1).atStartOfDay())) {
            qty.put((Long) r[0], (BigDecimal) r[1]);
        }
        Map<Long, Product> products = new HashMap<>();
        productRepository.findAllById(qty.keySet()).forEach(p -> products.put(p.getId(), p));
        List<Map.Entry<Product, BigDecimal>> boxesBy = new ArrayList<>();
        for (Map.Entry<Long, BigDecimal> e : qty.entrySet()) {
            Product p = products.get(e.getKey());
            if (p == null) continue;
            BigDecimal units = AdminOrders.boxUnits(p);
            BigDecimal b = units == null ? e.getValue() : e.getValue().divide(units, 1, RoundingMode.HALF_UP);
            boxesBy.add(Map.entry(p, b));
        }
        boxesBy.sort((a, b) -> b.getValue().compareTo(a.getValue()));
        BigDecimal max = boxesBy.isEmpty() ? BigDecimal.ONE : boxesBy.get(0).getValue().max(BigDecimal.ONE);
        for (Map.Entry<Product, BigDecimal> e : boxesBy.subList(0, Math.min(7, boxesBy.size()))) {
            top.add(new Top(e.getKey(), e.getValue(), e.getValue().doubleValue() / max.doubleValue() * 100));
        }
        model.addAttribute("top", top);
        return "admin/dashboard";
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
            boolean past = isToday && h < nowH;
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
