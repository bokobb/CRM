package com.confectionery.crm.admin;

import com.confectionery.crm.domain.*;
import com.confectionery.crm.repository.ClientRepository;
import com.confectionery.crm.repository.OrderRepository;
import com.confectionery.crm.repository.ProductRepository;
import com.confectionery.crm.repository.UserRepository;
import com.confectionery.crm.service.*;
import com.confectionery.crm.web.dto.OrderForm;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * Заказы в админке: список с фильтрами, карточка, новый заказ, изменение, смена статуса, перенос
 * выдачи, отмена, отметка оплаты. Всё через OrderService (те же методы, что у приложения), кнопки
 * статусов — из OrderStateMachine.availableTransitions.
 */
@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminOrderController {

    private final OrderRepository orderRepository;
    private final ClientRepository clientRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final OrderService orderService;
    private final OrderStateMachine stateMachine;
    private final PickupSlotService pickupSlotService;
    private final ClientPriceService clientPriceService;
    private final CurrentUserService currentUserService;
    private final AdminFormat fmt;

    static final int PAGE = 50;

    static final List<String[]> TABS = List.of(
            new String[]{"all", "Все"}, new String[]{"sent", "Новые"}, new String[]{"new", "Приняты"},
            new String[]{"work", "Собираются"}, new String[]{"ready", "Готовы к выдаче"},
            new String[]{"noshow", "Не забрали"}, new String[]{"done", "Выданы"}, new String[]{"off", "Отменены"});

    private static final Set<String> ACTIVE_TABS = Set.of("sent", "new", "work", "ready", "noshow");

    public record Tab(String key, String label, int count, boolean alert, String href, boolean on) {
    }

    public record Option(String value, String label, boolean on) {
    }

    // ------------------------------------------------------------------ список

    @GetMapping("/orders")
    @Transactional(readOnly = true)
    public String list(@RequestParam(defaultValue = "all") String status, @RequestParam(defaultValue = "all") String date,
                       @RequestParam(defaultValue = "all") String source, @RequestParam(defaultValue = "") String q,
                       @RequestParam(required = false) Long client, @RequestParam(defaultValue = "1") int page,
                       @RequestParam(defaultValue = "0") int unpaid, Model model) {
        String query = q.trim();
        String digitsOnly = query.replaceAll("^[#№\\s]+", "");
        if (digitsOnly.matches("\\d{1,7}") && query.replaceAll("[#№\\s]", "").equals(digitsOnly)) {
            Long id = Long.valueOf(digitsOnly);
            if (orderRepository.existsById(id)) return "redirect:/admin/orders/" + id;
        }
        List<Order> all = orderRepository.findAllByOrderByCreatedAtDesc();
        LocalDate today = LocalDate.now();
        List<AdminOrders.Row> base = new ArrayList<>();
        for (Order o : all) {
            if (client != null && !o.getClient().getId().equals(client)) continue;
            if (!matchQ(o, query)) continue;
            if (unpaid == 1 && !AdminOrders.unpaid(o)) continue;
            LocalDate d = AdminOrders.day(o);
            boolean dayOk = switch (date) {
                case "today" -> d.equals(today);
                case "tomorrow" -> d.equals(today.plusDays(1));
                case "week" -> !d.isBefore(today) && !d.isAfter(today.plusDays(6));
                case "past" -> d.isBefore(today);
                default -> true;
            };
            if (!dayOk) continue;
            boolean site = AdminOrders.byClient(o);
            if (source.equals("site") && !site || source.equals("crm") && site) continue;
            base.add(AdminOrders.row(o));
        }
        Map<String, Integer> counts = new HashMap<>();
        for (AdminOrders.Row r : base) counts.merge(r.badge(), 1, Integer::sum);
        String tab = TABS.stream().anyMatch(t -> t[0].equals(status)) ? status : "all";
        List<Tab> tabs = new ArrayList<>();
        for (String[] t : TABS) {
            int n = t[0].equals("all") ? base.size() : counts.getOrDefault(t[0], 0);
            if (!t[0].equals("all") && n == 0 && !tab.equals(t[0]) && (t[0].equals("noshow") || t[0].equals("off"))) continue;
            tabs.add(new Tab(t[0], t[1], n, (t[0].equals("noshow") || t[0].equals("sent")) && n > 0,
                    link(t[0], date, source, query, client, 1), tab.equals(t[0])));
        }
        List<AdminOrders.Row> list = new ArrayList<>(base.stream().filter(r -> tab.equals("all") || r.badge().equals(tab)).toList());
        if (ACTIVE_TABS.contains(tab)) {
            list.sort(Comparator.comparing(AdminOrders.Row::day)
                    .thenComparing(r -> r.order().getPickupHour() == null ? 99 : r.order().getPickupHour())
                    .thenComparing(AdminOrders.Row::id));
        } else {
            list.sort(Comparator.comparing(AdminOrders.Row::id).reversed());
        }
        int pages = Math.max(1, (list.size() + PAGE - 1) / PAGE);
        int p = Math.min(Math.max(1, page), pages);
        model.addAttribute("rows", list.subList((p - 1) * PAGE, Math.min(list.size(), p * PAGE)));
        model.addAttribute("total", list.size());
        model.addAttribute("allCount", all.size());
        model.addAttribute("page", p);
        model.addAttribute("pages", pages);
        model.addAttribute("prevHref", p > 1 ? link(tab, date, source, query, client, p - 1) : null);
        model.addAttribute("nextHref", p < pages ? link(tab, date, source, query, client, p + 1) : null);
        model.addAttribute("tabs", tabs);
        model.addAttribute("status", tab);
        model.addAttribute("q", query);
        model.addAttribute("client", client);
        model.addAttribute("clientName", client == null ? null : clientRepository.findById(client).map(Client::getName).orElse(null));
        model.addAttribute("dates", List.of(opt("all", "Выдача: любая", date), opt("today", "Выдача: сегодня", date),
                opt("tomorrow", "Выдача: завтра", date), opt("week", "Выдача: 7 дней", date), opt("past", "Выдача: прошла", date)));
        model.addAttribute("sources", List.of(opt("all", "Откуда: все", source), opt("site", "Откуда: сайт", source),
                opt("crm", "Откуда: менеджер", source)));
        model.addAttribute("filtered", !query.isEmpty() || !date.equals("all") || !source.equals("all") || client != null || !tab.equals("all"));
        model.addAttribute("title", "Заказы");
        model.addAttribute("sub", "");
        model.addAttribute("sec", "orders");
        return "admin/orders/list";
    }

    private static Option opt(String v, String l, String cur) {
        return new Option(v, l, v.equals(cur));
    }

    private static String link(String status, String date, String source, String q, Long client, int page) {
        StringBuilder sb = new StringBuilder("/admin/orders?status=").append(status);
        if (!date.equals("all")) sb.append("&date=").append(date);
        if (!source.equals("all")) sb.append("&source=").append(source);
        if (!q.isEmpty()) sb.append("&q=").append(java.net.URLEncoder.encode(q, java.nio.charset.StandardCharsets.UTF_8));
        if (client != null) sb.append("&client=").append(client);
        if (page > 1) sb.append("&page=").append(page);
        return sb.toString();
    }

    static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replace('ё', 'е').trim();
    }

    /** Поиск: номер, магазин, контактное лицо, цифры телефона (от трёх цифр). Все слова должны найтись. */
    static boolean matchQ(Order o, String q) {
        String nq = norm(q);
        if (nq.isEmpty()) return true;
        Client c = o.getClient();
        String hay = norm(o.getId() + " " + c.getName() + " " + (c.getContactPerson() == null ? "" : c.getContactPerson()));
        String phone = c.getPhone() == null ? "" : c.getPhone().replaceAll("\\D", "");
        for (String w : nq.split("\\s+")) {
            String wd = w.replaceAll("\\D", "");
            boolean phoneHit = wd.length() >= 3 && wd.equals(w.replaceAll("[\\s()+-]", "")) && phone.contains(wd);
            if (!hay.contains(w) && !phoneHit) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ карточка

    public record Line(Product product, BigDecimal qty, BigDecimal price, BigDecimal sum, BigDecimal perBox, String boxText,
                       String unit) {
    }

    @GetMapping("/orders/{id}")
    @Transactional(readOnly = true)
    public String view(@PathVariable Long id, Model model) {
        model.addAttribute("title", "Заказы");
        model.addAttribute("sub", "");
        model.addAttribute("sec", "orders");
        Order o = orderRepository.findById(id).orElse(null);
        if (o == null) {
            model.addAttribute("missing", id);
            return "admin/orders/view";
        }
        Role role = currentUserService.role().orElse(null);
        AdminOrders.Row row = AdminOrders.row(o);
        List<Line> lines = o.getItems().stream().map(i -> {
            BigDecimal perBox = AdminOrders.boxUnits(i.getProduct());
            String unit = i.getProduct().getUnit() == null || i.getProduct().getUnit().isBlank() ? "шт" : i.getProduct().getUnit();
            return new Line(i.getProduct(), i.getQuantity(), i.getPrice(), i.getPrice().multiply(i.getQuantity()), perBox,
                    fmt.boxText(i.getQuantity(), perBox), unit);
        }).toList();
        model.addAttribute("o", o);
        model.addAttribute("row", row);
        model.addAttribute("t", row.totals());
        model.addAttribute("lines", lines);
        model.addAttribute("hist", AdminOrders.history(o));
        model.addAttribute("acts", stateMachine.availableTransitions(o, role));
        model.addAttribute("canEdit", stateMachine.canEdit(o, role));
        model.addAttribute("canMove", stateMachine.canMove(o, role));
        model.addAttribute("canPay", stateMachine.canMarkPaid(o, role));
        model.addAttribute("isAdmin", role == Role.ADMIN);
        model.addAttribute("clientOrders", orderRepository.findByClientIdOrderByCreatedAtDesc(o.getClient().getId()).size());
        model.addAttribute("placedBy", AdminOrders.byClient(o)
                ? (o.getSource() == OrderSource.WHATSAPP ? "покупателем в WhatsApp" : "покупателем на сайте")
                : o.isImportedFromOneC() ? "в 1С" : "менеджером");
        model.addAttribute("doneAt", AdminOrders.doneAt(o));
        OrderEvent off = AdminOrders.last(o, OrderStatus.CANCELLED);
        model.addAttribute("offEvent", off);
        model.addAttribute("ret", o.getReturns().isEmpty() ? null : o.getReturns().get(0));
        // возврат к заказу: можно ли добавить или изменить, кто оформил (ФИО по логину), переплата, если заказ оплатили до возврата
        model.addAttribute("canReturn", ProductReturnService.acceptsReturn(o));
        Map<String, String> retBy = new HashMap<>();
        for (ProductReturn r : o.getReturns()) {
            if (r.getCreatedBy() != null && !retBy.containsKey(r.getCreatedBy())) {
                userRepository.findByUsername(r.getCreatedBy()).map(User::getFullName).filter(n -> !n.isBlank())
                        .ifPresent(n -> retBy.put(r.getCreatedBy(), n));
            }
        }
        model.addAttribute("retBy", retBy);
        BigDecimal overpay = BigDecimal.ZERO;
        if (o.isPaid() && o.getPaidAt() != null) {
            for (ProductReturn r : o.getReturns()) if (r.getCreatedAt().isAfter(o.getPaidAt())) overpay = overpay.add(r.getTotalSum());
        }
        model.addAttribute("overpay", overpay);
        model.addAttribute("slotWeek", AdminJson.of(slotWeek(o.getId())));
        model.addAttribute("stamp", OrderService.stamp(o));
        return "admin/orders/view";
    }

    /** Неделя окон для «Перенести» и формы заказа: дни с местами и окна. */
    List<Map<String, Object>> slotWeek(Long exceptId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PickupSlotService.Day d : pickupSlotService.week(exceptId)) {
            Map<String, Object> day = new LinkedHashMap<>();
            day.put("d", d.date().toString());
            day.put("free", d.free());
            List<Map<String, Object>> slots = new ArrayList<>();
            for (PickupSlotService.Slot s : d.slots()) {
                slots.add(Map.of("h", s.hour(), "left", s.left(), "cap", s.capacity(), "past", s.past()));
            }
            day.put("slots", slots);
            out.add(day);
        }
        return out;
    }

    // ------------------------------------------------------------------ действия

    private static final Map<OrderStatus, String> MSG = Map.of(
            OrderStatus.CONFIRMED, "принят", OrderStatus.NEW, "передан в сборку", OrderStatus.READY, "готов к выдаче",
            OrderStatus.DELIVERED, "выдан", OrderStatus.NO_SHOW, "отмечен: клиент не пришёл",
            OrderStatus.COMPLETED, "закрыт", OrderStatus.CANCELLED, "отменён");

    @PostMapping("/orders/{id}/transition")
    public String transition(@PathVariable Long id, @RequestParam OrderStatus target,
                             @RequestParam(required = false) OrderStatus expected,
                             @RequestParam(required = false) String vehicle,
                             @RequestParam(required = false) String reason, @RequestParam(required = false) String comment,
                             RedirectAttributes ra) {
        try {
            OrderStatus before = orderRepository.findById(id).map(Order::getStatus).orElse(null);
            String why = reason == null ? null : reason + (comment != null && !comment.isBlank() ? ": " + comment.trim() : "");
            Order o = orderService.transition(id, target, expected, vehicle, why);
            ra.addFlashAttribute("toast", "Заказ № " + id + " " + MSG.getOrDefault(target, "изменён"));
            Role role = currentUserService.role().orElse(null);
            if (before != null && before == stateMachine.rollbackTarget(o, role)) {
                ra.addFlashAttribute("toastUndo", "/admin/orders/" + id + "/rollback");
            }
        } catch (OrderService.ConcurrentChangeException e) {
            ra.addFlashAttribute("toast", e.getMessage());
        } catch (RuntimeException e) {
            ra.addFlashAttribute("toast", e.getMessage());
            ra.addFlashAttribute("toastError", true);
        }
        return "redirect:/admin/orders/" + id;
    }

    @PostMapping("/orders/{id}/cancel")
    public String cancel(@PathVariable Long id, @RequestParam(required = false) OrderStatus expected,
                         @RequestParam(defaultValue = "Другая причина") String reason,
                         @RequestParam(required = false) String comment, RedirectAttributes ra) {
        return transition(id, OrderStatus.CANCELLED, expected, null, reason, comment, ra);
    }

    @PostMapping("/orders/{id}/rollback")
    public String rollback(@PathVariable Long id, RedirectAttributes ra) {
        try {
            if (currentUserService.role().orElse(null) != Role.ADMIN) throw new IllegalStateException("Вернуть статус может только директор");
            orderService.rollback(id);
            ra.addFlashAttribute("toast", "Вернули как было.");
        } catch (RuntimeException e) {
            ra.addFlashAttribute("toast", e.getMessage());
            ra.addFlashAttribute("toastError", true);
        }
        return "redirect:/admin/orders/" + id;
    }

    @PostMapping("/orders/{id}/move")
    public String move(@PathVariable Long id, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                       @RequestParam Integer hour, RedirectAttributes ra) {
        try {
            orderService.move(id, date, hour);
            ra.addFlashAttribute("toast", "Выдача № " + id + " перенесена на " + fmt.dm(date) + ", " + fmt.win(hour));
        } catch (RuntimeException e) {
            ra.addFlashAttribute("toast", e.getMessage());
            ra.addFlashAttribute("toastError", true);
        }
        return "redirect:/admin/orders/" + id;
    }

    @PostMapping("/orders/{id}/paid")
    public String paid(@PathVariable Long id, RedirectAttributes ra) {
        try {
            orderService.markPaid(id);
            ra.addFlashAttribute("toast", "Оплата по заказу № " + id + " отмечена");
        } catch (RuntimeException e) {
            ra.addFlashAttribute("toast", e.getMessage());
            ra.addFlashAttribute("toastError", true);
        }
        return "redirect:/admin/orders/" + id;
    }

    // ------------------------------------------------------------------ новый заказ, изменение

    /** Состояние формы — то же, что JS держит в памяти (order-form.js), отдаётся JSON-ом в страницу. */
    public record FormState(Long no, String status, String stamp, Long client, String day, Integer hour, String pay,
                            String note, List<Map<String, Object>> rows) {
    }

    @GetMapping("/orders/new")
    @Transactional(readOnly = true)
    public String newForm(@RequestParam(required = false) Long repeat, @RequestParam(required = false) Long client, Model model) {
        Order src = repeat == null ? null : orderRepository.findById(repeat).orElse(null);
        List<Map<String, Object>> rows = new ArrayList<>();
        Long clientId = client;
        String pay = null;
        if (src != null) {
            clientId = src.getClient().getId();
            pay = src.getPaymentMethod() == null ? null : src.getPaymentMethod().name();
            for (OrderItem i : src.getItems()) {
                if (i.getProduct().isActive()) rows.add(Map.of("key", i.getProduct().getId(), "qty", i.getQuantity().stripTrailingZeros().toPlainString()));
            }
        }
        if (pay == null && clientId != null) {
            pay = clientRepository.findById(clientId).map(c -> c.getDefaultPayment() == null ? null : c.getDefaultPayment().name()).orElse(null);
        }
        FormState st = new FormState(null, null, null, clientId, null, null, pay == null ? "CASH" : pay, "", rows);
        return form(st, src != null, List.of(), model);
    }

    @GetMapping("/orders/{id}/edit")
    @Transactional(readOnly = true)
    public String editForm(@PathVariable Long id, Model model, RedirectAttributes ra) {
        Order o = orderRepository.findById(id).orElse(null);
        if (o == null) return "redirect:/admin/orders/" + id;
        if (!stateMachine.canEdit(o, currentUserService.role().orElse(null))) {
            ra.addFlashAttribute("toast", "Изменить можно только заказ «Новая» или «Принят» — до начала сборки");
            return "redirect:/admin/orders/" + id;
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (OrderItem i : o.getItems()) {
            rows.add(Map.of("key", i.getProduct().getId(), "qty", i.getQuantity().stripTrailingZeros().toPlainString()));
        }
        FormState st = new FormState(o.getId(), o.getStatus().badge(), OrderService.stamp(o), o.getClient().getId(),
                o.getDueDate() == null ? null : o.getDueDate().toString(), o.getPickupHour(),
                o.getPaymentMethod() == null ? "" : o.getPaymentMethod().name(), o.getNotes() == null ? "" : o.getNotes(), rows);
        model.addAttribute("o", o);
        return form(st, false, List.of(), model);
    }

    private String form(FormState st, boolean repeat, List<String> errors, Model model) {
        model.addAttribute("title", "Заказы");
        model.addAttribute("sub", "");
        model.addAttribute("sec", "orders");
        model.addAttribute("st", st);
        model.addAttribute("repeat", repeat);
        model.addAttribute("errors", errors);
        model.addAttribute("errorsJson", AdminJson.of(errors));
        model.addAttribute("stateJson", AdminJson.of(st));
        model.addAttribute("catalogJson", AdminJson.of(catalog(st.client())));
        model.addAttribute("clientsJson", AdminJson.of(clients()));
        model.addAttribute("slotsJson", AdminJson.of(slotWeek(st.no())));
        model.addAttribute("payments", PaymentMethod.values());
        return "admin/orders/form";
    }

    /** Товары для подсказок: id, название, цена клиента (или из прайса), коробка, вес, объём, фото. */
    List<Map<String, Object>> catalog(Long clientId) {
        Client client = clientId == null ? null : clientRepository.findById(clientId).orElse(null);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Product p : productRepository.findByActiveTrue()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", p.getId());
            m.put("label", p.getName());
            m.put("price", client != null ? clientPriceService.resolvePrice(client, p) : p.getPrice());
            BigDecimal box = AdminOrders.boxUnits(p);
            m.put("box", box == null ? null : box.stripTrailingZeros().toPlainString());
            BigDecimal g = AdminOrders.grams(p);
            m.put("g", g == null ? 0 : g);
            m.put("vol", p.getBoxVolumeM3() == null ? 0 : p.getBoxVolumeM3());
            m.put("photo", AdminOrders.photo(p));
            out.add(m);
        }
        out.sort(Comparator.comparing(m -> m.get("label").toString(), String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    List<Map<String, Object>> clients() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Client c : clientRepository.findAll()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", c.getId());
            m.put("org", c.getName());
            m.put("person", c.getContactPerson() == null ? "" : c.getContactPerson());
            m.put("phone", c.getPhone() == null ? "" : c.getPhone());
            m.put("pay", c.getDefaultPayment() == null ? "" : c.getDefaultPayment().name());
            out.add(m);
        }
        return out;
    }

    @PostMapping("/orders")
    public String create(HttpServletRequest req, Model model, RedirectAttributes ra) {
        return save(null, req, model, ra);
    }

    @PostMapping("/orders/{id}")
    public String update(@PathVariable Long id, HttpServletRequest req, Model model, RedirectAttributes ra) {
        return save(id, req, model, ra);
    }

    /** Проверки — как в форме, но на сервере: клиент, окно, строки, целые количества больше нуля, активные товары. */
    private String save(Long id, HttpServletRequest req, Model model, RedirectAttributes ra) {
        String clientRaw = req.getParameter("client");
        Long clientId = clientRaw == null || clientRaw.isBlank() ? null : Long.valueOf(clientRaw);
        String dayRaw = req.getParameter("day");
        String hourRaw = req.getParameter("hour");
        LocalDate day = dayRaw == null || dayRaw.isBlank() ? null : LocalDate.parse(dayRaw);
        Integer hour = hourRaw == null || hourRaw.isBlank() ? null : Integer.valueOf(hourRaw);
        String payRaw = req.getParameter("pay");
        PaymentMethod pay = payRaw == null || payRaw.isBlank() ? null : PaymentMethod.valueOf(payRaw);
        String note = Optional.ofNullable(req.getParameter("note")).orElse("");
        if (note.length() > 500) note = note.substring(0, 500);
        String[] keys = Optional.ofNullable(req.getParameterValues("lineProduct")).orElse(new String[0]);
        String[] qtys = Optional.ofNullable(req.getParameterValues("lineQty")).orElse(new String[0]);
        List<Map<String, Object>> rows = new ArrayList<>();
        List<OrderService.Line> lines = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        boolean badLine = false;
        for (int i = 0; i < keys.length; i++) {
            String k = keys[i].trim();
            String q = i < qtys.length ? qtys[i].trim() : "";
            if (k.isEmpty() && q.isEmpty()) continue;
            Map<String, Object> r = new HashMap<>();
            r.put("key", k.isEmpty() ? "" : Long.valueOf(k));
            r.put("qty", q);
            rows.add(r);
            if (k.isEmpty() || !q.matches("\\d{1,5}") || Integer.parseInt(q) <= 0) {
                badLine = true;
                continue;
            }
            Product p = productRepository.findById(Long.valueOf(k)).orElse(null);
            if (p == null || !p.isActive()) {
                errors.add("Товар «" + (p == null ? k : p.getName()) + "» снят с продажи.");
                continue;
            }
            lines.add(new OrderService.Line(p.getId(), new BigDecimal(q)));
        }
        if (clientId == null) errors.add(0, "Выберите клиента.");
        if (day == null || hour == null) errors.add("Выберите день и окно выдачи.");
        if (lines.isEmpty() && !badLine) errors.add("Добавьте хотя бы один товар с количеством.");
        else if (badLine) errors.add("Проверьте выделенные строки: товар из каталога и количество.");
        String next = req.getParameter("next");
        FormState st = new FormState(id, req.getParameter("status"), req.getParameter("stamp"), clientId,
                day == null ? null : day.toString(), hour, payRaw == null ? "" : payRaw, note, rows);
        if (errors.isEmpty()) {
            try {
                Order o;
                if (id == null) {
                    OrderForm form = new OrderForm();
                    form.setClientId(clientId);
                    form.setDueDate(day);
                    form.setPickupHour(hour);
                    form.setPaymentMethod(pay);
                    form.setNotes(note.isBlank() ? null : note.trim());
                    for (OrderService.Line l : lines) {
                        OrderForm.OrderItemForm f = new OrderForm.OrderItemForm();
                        f.setProductId(l.productId());
                        f.setQuantity(l.quantity());
                        form.getItems().add(f);
                    }
                    o = orderService.createOrder(form);
                    ra.addFlashAttribute("toast", "Заказ № " + o.getId() + " создан и принят");
                } else {
                    o = orderService.updateOrder(id, req.getParameter("stamp"), clientId, day, hour, lines, pay, note);
                    ra.addFlashAttribute("toast", "Заказ № " + o.getId() + " сохранён");
                }
                if (next != null && next.startsWith("/admin/")) return "redirect:" + next;
                if (next != null && next.contains("/admin/")) return "redirect:" + next.substring(next.indexOf("/admin/"));
                return "redirect:/admin/orders/" + o.getId();
            } catch (OrderService.ConcurrentChangeException e) {
                ra.addFlashAttribute("toast", e.getMessage());
                return "redirect:/admin/orders/" + id;
            } catch (RuntimeException e) {
                errors.add(e.getMessage());
            }
        }
        if (id != null) orderRepository.findById(id).ifPresent(o -> model.addAttribute("o", o));
        return form(st, false, errors, model);
    }

    // ------------------------------------------------------------------ JSON для форм

    @GetMapping("/api/slots")
    @ResponseBody
    public Map<String, Object> slots(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                     @RequestParam(required = false) Long except) {
        PickupSlotService.Day d = pickupSlotService.day(date, except);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("d", d.date().toString());
        out.put("free", d.free());
        out.put("slots", d.slots().stream().map(s -> Map.of("h", s.hour(), "left", s.left(), "cap", s.capacity(), "past", s.past())).toList());
        return out;
    }

    @GetMapping("/api/clients")
    @ResponseBody
    public List<Map<String, Object>> clientSuggest(@RequestParam(defaultValue = "") String q) {
        String nq = norm(q);
        String digits = nq.replaceAll("\\D", "");
        return clients().stream().filter(c -> {
            String hay = norm(c.get("org") + " " + c.get("person"));
            return nq.isEmpty() || hay.contains(nq) || (digits.length() >= 3 && c.get("phone").toString().replaceAll("\\D", "").contains(digits));
        }).limit(6).toList();
    }

    /** Цены для выбранного клиента (индивидуальный прайс) — форма пересчитывает суммы при смене клиента. */
    @GetMapping("/api/clients/{id}/prices")
    @ResponseBody
    public Map<Long, BigDecimal> prices(@PathVariable Long id) {
        Client client = clientRepository.findById(id).orElseThrow();
        Map<Long, BigDecimal> out = new HashMap<>();
        for (Product p : productRepository.findByActiveTrue()) out.put(p.getId(), clientPriceService.resolvePrice(client, p));
        return out;
    }
}
