package com.confectionery.crm.admin;

import com.confectionery.crm.domain.*;
import com.confectionery.crm.repository.CompanyProfileRepository;
import com.confectionery.crm.repository.OrderRepository;
import com.confectionery.crm.repository.ProductRepository;
import com.confectionery.crm.repository.ProductReturnRepository;
import com.confectionery.crm.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Predicate;

import static com.confectionery.crm.admin.AdminSupport.*;

/**
 * Клиенты, как в макете: список с отборами («С долгом», «Пора напомнить», «Новые», «По счёту») и порядком, карточка —
 * сколько выдано, средний заказ, как часто заказывает, долг, заказы, что берёт (в штуках) и «обычный заказ», возвраты
 * в штуках, оплата и долг с «Отметить оплату», контакты, заметки, индивидуальные цены, напоминание в WhatsApp;
 * новый клиент и изменение — в окне. Плюс заявки на доступ к сайту, «Перезвоните мне» и долги по счетам.
 */
@Controller
@RequestMapping("/admin/clients")
@RequiredArgsConstructor
public class AdminClientController {

    private final ClientService clientService;
    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final ProductReturnRepository productReturnRepository;
    private final CompanyProfileRepository companyProfileRepository;
    private final ClientPriceService clientPriceService;
    private final ShopAccessService shopAccessService;
    private final OrderService orderService;
    private final OrderPriceFillService orderPriceFillService;
    private final CurrentUserService currentUserService;
    private final AdminFormat fmt;

    // ------------------------------------------------------------------ список

    /** Строка списка: клиент, его показатели и готовые подписи ячеек. */
    public record Row(Client client, AdminClients.Stats s, String initial, String orTitle, String orSub, String lastTitle,
                      String lastSub, boolean lastBad, String debtSub, boolean debtBad) {
    }

    public record Seg(String key, String label, long count, boolean alert, String href, boolean on) {
    }

    public record Opt(String value, String label, boolean on) {
    }

    static final List<String[]> SEGS = List.of(new String[]{"all", "Все"}, new String[]{"debt", "С долгом"},
            new String[]{"remind", "Пора напомнить"}, new String[]{"new", "Новые"}, new String[]{"invoice", "По счёту"});
    static final List<String[]> SORTS = List.of(new String[]{"att", "Сначала важные"}, new String[]{"sum", "По сумме за 30 дней"},
            new String[]{"last", "По последней выдаче"}, new String[]{"name", "По названию"});

    @GetMapping
    @Transactional(readOnly = true)
    public String list(@RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "all") String seg,
                       @RequestParam(defaultValue = "att") String sort, Model model) {
        LocalDate today = LocalDate.now();
        Map<Long, List<Order>> byClient = new HashMap<>();
        for (Order o : orderRepository.findAll()) byClient.computeIfAbsent(o.getClient().getId(), k -> new ArrayList<>()).add(o);
        String nq = AdminOrderController.norm(q);
        String digits = nq.replaceAll("\\D", "");
        List<Row> all = new ArrayList<>(), base = new ArrayList<>();
        BigDecimal debtAll = BigDecimal.ZERO, lateAll = BigDecimal.ZERO;
        List<Client> clients = clientService.findAll();
        for (Client c : clients) {
            AdminClients.Stats s = AdminClients.stats(c, byClient.getOrDefault(c.getId(), List.of()), today);
            Row r = row(c, s);
            all.add(r);
            debtAll = debtAll.add(s.debt());
            lateAll = lateAll.add(s.lateSum());
            String hay = AdminOrderController.norm(c.getName() + " " + (c.getContactPerson() == null ? "" : c.getContactPerson()) + " "
                    + (c.getAddress() == null ? "" : c.getAddress()));
            boolean phone = digits.length() >= 3 && c.getPhone() != null && c.getPhone().replaceAll("\\D", "").contains(digits);
            boolean words = Arrays.stream(nq.split("\\s+")).allMatch(hay::contains);
            if (nq.isEmpty() || words || phone) base.add(r);
        }
        Map<String, Predicate<Row>> segOf = Map.of(
                "all", r -> true,
                "debt", r -> r.s().debt().signum() > 0,
                "remind", r -> r.s().st().equals("remind"),
                "new", r -> r.s().st().equals("new"),
                "invoice", r -> r.client().getDefaultPayment() == PaymentMethod.INVOICE);
        String segOn = segOf.containsKey(seg) ? seg : "all";
        String sortOn = SORTS.stream().anyMatch(x -> x[0].equals(sort)) ? sort : "att";
        List<Seg> segs = new ArrayList<>();
        for (String[] g : SEGS) {
            long n = base.stream().filter(segOf.get(g[0])).count();
            segs.add(new Seg(g[0], g[1], n, (g[0].equals("debt") || g[0].equals("remind")) && n > 0, link(q, g[0], sortOn), g[0].equals(segOn)));
        }
        List<Row> list = new ArrayList<>(base.stream().filter(segOf.get(segOn)).toList());
        list.sort(switch (sortOn) {
            case "sum" -> Comparator.comparing((Row r) -> r.s().sum30()).reversed();
            case "last" -> Comparator.comparing((Row r) -> r.s().last(), Comparator.nullsFirst(Comparator.<LocalDate>naturalOrder())).reversed();
            case "name" -> Comparator.comparing((Row r) -> AdminClients.nameKey(r.client().getName()));
            default -> Comparator.comparing((Row r) -> AdminClients.ST_ORDER.get(r.s().st()))
                    .thenComparing((a, b) -> {
                        String st = a.s().st();
                        if (st.equals("debt")) return b.s().debt().compareTo(a.s().debt());
                        if (st.equals("remind")) return Long.compare(b.s().gap() == null ? 0 : b.s().gap(), a.s().gap() == null ? 0 : a.s().gap());
                        return b.s().sum30().compareTo(a.s().sum30());
                    })
                    .thenComparing(r -> AdminClients.nameKey(r.client().getName()));
        });
        model.addAttribute("rows", list);
        model.addAttribute("segs", segs);
        model.addAttribute("sorts", SORTS.stream().map(x -> new Opt(x[0], x[1], x[0].equals(sortOn))).toList());
        model.addAttribute("q", q);
        model.addAttribute("seg", segOn);
        model.addAttribute("sort", sortOn);
        model.addAttribute("total", clients.size());
        model.addAttribute("debtAll", debtAll);
        model.addAttribute("lateAll", lateAll);
        model.addAttribute("isAdmin", currentUserService.hasRole(Role.ADMIN));
        model.addAttribute("accessCount", currentUserService.hasRole(Role.ADMIN) ? shopAccessService.pending().size() : 0);
        model.addAttribute("callbackCount", shopAccessService.callbacks().stream().filter(c -> "NEW".equals(c.getStatus())).count());
        model.addAttribute("debtCount", orderService.findUnpaidDebts().size());
        model.addAttribute("phonesJson", AdminJson.of(phones(clients)));
        page(model, "Клиенты", "магазины, кафе и оптовики", "clients");
        return "admin/clients/list";
    }

    private static String link(String q, String seg, String sort) {
        StringBuilder sb = new StringBuilder("/admin/clients?seg=").append(seg);
        if (!sort.equals("att")) sb.append("&sort=").append(sort);
        if (!q.isBlank()) sb.append("&q=").append(URLEncoder.encode(q.trim(), StandardCharsets.UTF_8));
        return sb.toString();
    }

    Row row(Client c, AdminClients.Stats s) {
        String orTitle = s.n() > 0 ? fmt.count(s.n(), "заказ", "заказа", "заказов") : "0";
        String orSub = s.interval() != null ? "раз в " + fmt.count(s.interval(), "день", "дня", "дней") : s.n() > 0 ? "пока один" : "ещё не заказывал";
        String lastTitle, lastSub;
        boolean lastBad = false;
        if (!s.active().isEmpty()) {
            Order a = s.active().get(0);
            lastTitle = "№ " + a.getId() + " · " + a.getStatus().badgeLabel().toLowerCase(Locale.ROOT);
            lastSub = "выдача " + fmt.relDay(AdminOrders.day(a));
        } else if (s.last() != null) {
            lastTitle = "выдан " + fmt.dm(s.last());
            lastSub = s.gap() != null && s.gap() > 0 ? fmt.count(s.gap(), "день", "дня", "дней") + " назад" : "сегодня";
            lastBad = s.st().equals("remind");
        } else {
            lastTitle = "—";
            lastSub = "заказов нет";
        }
        String debtSub = null;
        boolean debtBad = false;
        if (s.debt().signum() > 0) {
            if (s.late() != null && s.late() > 0) { debtSub = "просрочен " + s.late() + AdminFormat.NB + "дн."; debtBad = true; }
            else if (s.late() != null && s.late() == 0) { debtSub = "срок сегодня"; debtBad = true; }
            else if (s.due() != null) debtSub = "до " + fmt.dm(s.due());
            else debtSub = "не оплачен";
        }
        return new Row(c, s, AdminClients.initial(c.getName()), orTitle, orSub, lastTitle, lastSub, lastBad, debtSub, debtBad);
    }

    /** Для проверки в окне «Новый клиент»: телефон у двух клиентов совпадать не может. */
    static List<Map<String, Object>> phones(List<Client> clients) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Client c : clients) {
            String key = AdminClients.phoneKey(c.getPhone());
            if (key.isEmpty()) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("name", c.getName());
            m.put("key", key);
            out.add(m);
        }
        return out;
    }

    // ------------------------------------------------------------------ карточка

    public record Kpi(String label, String value, String unit, String sub, String cls) {
    }

    public record Ret(ProductReturn r, BigDecimal pcs) {
    }

    @GetMapping("/{id:\\d+}")
    @Transactional(readOnly = true)
    public String view(@PathVariable Long id, Model model) {
        Client c = clientService.findById(id);
        LocalDate today = LocalDate.now();
        List<Order> orders = orderRepository.findByClientIdOrderByCreatedAtDesc(id);
        AdminClients.Stats s = AdminClients.stats(c, orders, today);
        List<AdminClients.Buy> buys = AdminClients.buys(s);
        long usualN = buys.stream().filter(AdminClients.Buy::usual).count();

        // показатели
        String rhythm;
        if (!s.active().isEmpty()) {
            Order a = s.active().get(0);
            rhythm = "в работе заказ № " + a.getId() + ", выдача " + fmt.relDay(AdminOrders.day(a));
        } else if (s.next() != null) {
            rhythm = s.next().isBefore(today) ? "ждали заказ к " + fmt.dm(s.next()) + " — прошло " + fmt.count(s.gap(), "день", "дня", "дней") + " с выдачи"
                    : "следующий ≈ " + fmt.relDay(s.next());
        } else {
            rhythm = s.n() > 0 ? "пока один заказ — ритма ещё нет" : "заказов ещё не было";
        }
        String debtSub;
        if (s.debt().signum() > 0) {
            if (s.late() != null && s.late() > 0) debtSub = "просрочен " + s.late() + AdminFormat.NB + "дн.";
            else if (s.late() != null && s.late() == 0) debtSub = "срок сегодня";
            else if (s.due() != null) debtSub = "срок " + fmt.dm(s.due());
            else debtSub = "выданы и не оплачены: " + fmt.count(s.debts().size(), "заказ", "заказа", "заказов");
        } else debtSub = "долга нет";
        List<Kpi> kpis = List.of(
                new Kpi("Выдано заказов", String.valueOf(s.n()), null, s.n() > 0 ? "на " + fmt.money(s.sum()) : s.since() != null ? "клиент с " + fmt.dm(s.since()) : "—", ""),
                new Kpi("Средний заказ", s.n() > 0 ? fmt.dec(s.avg(), 0) : "—", s.n() > 0 ? "₸" : null,
                        s.n() > 0 ? "≈" + AdminFormat.NB + fmt.dec(s.avgBoxes(), 0) + AdminFormat.NB + "кор." : "—", ""),
                new Kpi("Заказывает", s.interval() != null ? "раз в " + s.interval() : "—",
                        s.interval() != null ? fmt.plural(s.interval(), "день", "дня", "дней") : null, rhythm, s.st().equals("remind") ? "kpi--warm" : ""),
                new Kpi("Долг", fmt.dec(s.debt(), 0), "₸", debtSub, s.hasOverdue() ? "kpi--alert" : ""));

        // возвраты: штук от отгруженного — у клиента и в среднем по всем клиентам
        List<Ret> rets = productReturnRepository.findByClientIdOrderByCreatedAtDesc(id).stream()
                .map(r -> new Ret(r, ProductReturnService.pieces(r))).toList();
        BigDecimal retPcs = rets.stream().map(Ret::pcs).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal shipPcs = BigDecimal.ZERO;
        for (Order o : s.done()) for (OrderItem i : o.getItems()) shipPcs = shipPcs.add(i.getQuantity());
        BigDecimal allRet = BigDecimal.ZERO, allShip = BigDecimal.ZERO;
        for (ProductReturn r : productReturnRepository.findAll()) allRet = allRet.add(ProductReturnService.pieces(r));
        for (Object[] x : orderRepository.soldByProduct(LocalDate.of(2000, 1, 1).atStartOfDay(), LocalDateTime.now().plusDays(1))) {
            allShip = allShip.add((BigDecimal) x[1]);
        }
        String retLine = shipPcs.signum() > 0
                ? "вернул " + fmt.num(retPcs) + AdminFormat.NB + "шт — " + pct(retPcs, shipPcs) + AdminFormat.NB + "% от отгруженного · в среднем по клиентам "
                + pct(allRet, allShip) + AdminFormat.NB + "%"
                : "выданных заказов нет";

        // сайт: входит ли по этому телефону
        Order site = orders.stream().filter(AdminOrders::byClient).filter(o -> o.getCreatedAt() != null).max(Comparator.comparing(Order::getCreatedAt)).orElse(null);
        model.addAttribute("c", c);
        model.addAttribute("s", s);
        model.addAttribute("initial", AdminClients.initial(c.getName()));
        model.addAttribute("kpis", kpis);
        model.addAttribute("orders", s.orders().stream().limit(8).map(AdminOrders::row).toList());
        model.addAttribute("orderCount", s.orders().size());
        model.addAttribute("buys", buys.stream().limit(8).toList());
        model.addAttribute("usualN", usualN);
        model.addAttribute("buysFrom", s.done().isEmpty() ? null : AdminClients.doneDay(s.done().get(0)));
        model.addAttribute("rets", rets);
        model.addAttribute("retLine", retLine);
        model.addAttribute("siteAt", site == null ? null : site.getCreatedAt());
        model.addAttribute("canPay", currentUserService.hasRole(Role.ADMIN, Role.ACCOUNTANT));
        model.addAttribute("prices", clientPriceService.findByClient(id));
        model.addAttribute("canPrice", currentUserService.hasRole(Role.ADMIN, Role.SALES, Role.ACCOUNTANT));
        model.addAttribute("isAdmin", currentUserService.hasRole(Role.ADMIN));
        model.addAttribute("products", productRepository.findByActiveTrue().stream()
                .sorted(Comparator.comparing(Product::getName, String.CASE_INSENSITIVE_ORDER)).toList());
        model.addAttribute("waText", waText(c, s));
        model.addAttribute("waPhone", waPhone(c.getPhone()));
        model.addAttribute("clientJson", AdminJson.of(dialogData(c)));
        model.addAttribute("phonesJson", AdminJson.of(phones(clientService.findAll())));
        page(model, "Клиенты", "", "clients");
        return "admin/clients/view";
    }

    static String pct(BigDecimal part, BigDecimal whole) {
        if (whole == null || whole.signum() == 0) return "0,0";
        return part.multiply(BigDecimal.valueOf(100)).divide(whole, 1, RoundingMode.HALF_UP).toPlainString().replace('.', ',');
    }

    /** Текст напоминания: обращение по имени, обычный набор (до пяти товаров) — как в макете. */
    String waText(Client c, AdminClients.Stats s) {
        String p = c.getContactPerson() == null ? "" : c.getContactPerson().trim();
        String name = p.isEmpty() || p.startsWith("ИП") ? "" : p.split("[\\s,]")[0];
        CompanyProfile cp = companyProfileRepository.findAll().stream().findFirst().orElse(null);
        String company = cp == null || cp.getName() == null || cp.getName().isBlank() ? "«Юг Сладости»" : cp.getName().trim();
        String lead = s.st().equals("remind") ? "Давно не было заказа от вас — собрать на завтра обычный набор?" : "Собрать вам заказ на завтра?";
        StringBuilder sb = new StringBuilder("Здравствуйте").append(name.isEmpty() ? "" : ", " + name).append("! Это ").append(company).append(". ").append(lead);
        int k = 0;
        for (Map<String, Object> l : AdminClients.usual(s)) {
            if (k++ == 5) break;
            Product pr = productRepository.findById(((Number) l.get("key")).longValue()).orElse(null);
            if (pr == null) continue;
            BigDecimal qty = new BigDecimal(l.get("qty").toString());
            String box = fmt.boxText(qty, AdminOrders.boxUnits(pr));
            sb.append("\n• ").append(pr.getName()).append(" — ").append(box.isEmpty() ? fmt.num(qty) + AdminFormat.NB + "шт" : box);
        }
        sb.append("\nМожно ответить на это сообщение или заказать на сайте.");
        return sb.toString();
    }

    /** Номер для wa.me: 7XXXXXXXXXX; null — телефона нет или он не казахстанский/российский. */
    static String waPhone(String phone) {
        String f = AdminClients.phoneFormat(phone);
        if (f == null || !f.startsWith("+7 ")) return null;
        return f.replaceAll("\\D", "");
    }

    /** Данные для окна «Изменить клиента». */
    static Map<String, Object> dialogData(Client c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("name", c.getName());
        m.put("contactPerson", c.getContactPerson() == null ? "" : c.getContactPerson());
        m.put("phone", c.getPhone() == null ? "" : c.getPhone());
        m.put("defaultPayment", c.getDefaultPayment() == null ? "CASH" : c.getDefaultPayment().name());
        m.put("bin", c.getBin() == null ? "" : c.getBin());
        m.put("email", c.getEmail() == null ? "" : c.getEmail());
        m.put("address", c.getAddress() == null ? "" : c.getAddress());
        return m;
    }

    /** Заметка о клиенте; {@code next} — «Сохранить и выйти» из окна «Сохранить изменения?». */
    @PostMapping("/{id:\\d+}/note")
    public String note(@PathVariable Long id, @RequestParam(defaultValue = "") String notes,
                       @RequestParam(required = false) String next, RedirectAttributes ra) {
        try {
            Client c = clientService.findById(id);
            String v = notes.trim();
            c.setNotes(v.isEmpty() ? null : v.length() > 2000 ? v.substring(0, 2000) : v);
            clientService.save(c);
            ok(ra, "Заметка сохранена");
        } catch (RuntimeException e) {
            fail(ra, e);
        }
        return next != null && next.startsWith("/admin/") ? "redirect:" + next : "redirect:/admin/clients/" + id;
    }

    // ------------------------------------------------------------------ новый клиент и изменение

    @GetMapping({"/new", "/{id:\\d+}/edit"})
    @Transactional(readOnly = true)
    public String form(@PathVariable(required = false) Long id, Model model) {
        model.addAttribute("c", id == null ? new Client() : clientService.findById(id));
        model.addAttribute("errors", List.of());
        model.addAttribute("payments", PaymentMethod.values());
        page(model, "Клиенты", "", "clients");
        return "admin/clients/form";
    }

    @PostMapping({"", "/{id:\\d+}"})
    public String save(@PathVariable(required = false) Long id, @RequestParam Map<String, String> f, Model model, RedirectAttributes ra) {
        Client c = id == null ? new Client() : clientService.findById(id);
        List<String> errors = apply(c, f);
        if (errors.isEmpty()) {
            try {
                Client saved = clientService.save(c);
                ok(ra, id == null ? "Добавили клиента: " + saved.getName() : "Данные клиента сохранены");
                return "redirect:/admin/clients/" + saved.getId();
            } catch (RuntimeException e) {
                errors.add(e.getMessage());
            }
        }
        model.addAttribute("c", c);
        model.addAttribute("errors", errors);
        model.addAttribute("payments", PaymentMethod.values());
        page(model, "Клиенты", "", "clients");
        return "admin/clients/form";
    }

    /**
     * То же из окна без ухода со страницы (форма заказа): JSON туда и обратно. Ответ — клиент в том же виде, что
     * подсказки клиентов формы заказа, или 400 с текстом ошибок.
     */
    @PostMapping({"/json", "/{id:\\d+}/json"})
    @ResponseBody
    public ResponseEntity<Map<String, Object>> saveJson(@PathVariable(required = false) Long id, @RequestBody Map<String, String> f) {
        Client c = id == null ? new Client() : clientService.findById(id);
        List<String> errors = apply(c, f);
        if (!errors.isEmpty()) return ResponseEntity.badRequest().body(Map.of("message", String.join(" ", errors)));
        Client saved = clientService.save(c);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", saved.getId());
        m.put("org", saved.getName());
        m.put("person", saved.getContactPerson() == null ? "" : saved.getContactPerson());
        m.put("phone", saved.getPhone() == null ? "" : saved.getPhone());
        m.put("pay", saved.getDefaultPayment() == null ? "" : saved.getDefaultPayment().name());
        return ResponseEntity.ok(m);
    }

    /**
     * Поля формы → клиент, с проверками: название; телефон — +7 и 10 цифр, приводится к «+7 700 000 00 11», у двух
     * клиентов совпадать не может (по нему входят на сайт); БИН/ИИН — 12 цифр. Старые телефоны в другом виде не трогаем,
     * пока их не меняют.
     */
    List<String> apply(Client c, Map<String, String> f) {
        List<String> errors = new ArrayList<>();
        String oldKey = AdminClients.phoneKey(c.getPhone());
        c.setName(f.getOrDefault("name", "").trim());
        c.setContactPerson(blank(f.get("contactPerson")));
        if (f.containsKey("email")) c.setEmail(blank(f.get("email")));
        if (f.containsKey("address")) c.setAddress(blank(f.get("address")));
        String bin = blank(f.get("bin"));
        c.setBin(bin == null ? null : bin.replaceAll("\\s", ""));
        if (f.containsKey("notes")) c.setNotes(blank(f.get("notes")));
        String pay = f.get("defaultPayment");
        try {
            c.setDefaultPayment(pay == null || pay.isBlank() ? null : PaymentMethod.valueOf(pay));
        } catch (IllegalArgumentException e) {
            errors.add("Способ оплаты не распознан.");
        }
        if (c.getName().isEmpty()) errors.add("Укажите название магазина или имя покупателя.");
        String phone = blank(f.get("phone"));
        String key = AdminClients.phoneKey(phone);
        if (phone == null) {
            // без телефона на сайт не войти: новому клиенту он обязателен; у старых из 1С его может не быть
            if (c.getId() == null || !oldKey.isEmpty()) errors.add("Телефон: +7 и ещё 10 цифр — по нему клиент входит на сайт.");
            else c.setPhone(null);
        } else if (key.equals(oldKey) && c.getId() != null) {
            c.setPhone(phone);                       // номер не меняли — как был
        } else {
            String formatted = AdminClients.phoneFormat(phone);
            if (formatted == null || !formatted.startsWith("+7 ")) {
                errors.add("Телефон: +7 и ещё 10 цифр.");
                c.setPhone(phone);
            } else {
                c.setPhone(formatted);
                for (Client o : clientService.findAll()) {
                    if (!Objects.equals(o.getId(), c.getId()) && AdminClients.phoneKey(o.getPhone()).equals(key)) {
                        errors.add("Этот номер уже у клиента " + o.getName() + ". По номеру входят на сайт, поэтому у двух клиентов он совпадать не может.");
                        break;
                    }
                }
            }
        }
        if (c.getBin() != null && !c.getBin().matches("\\d{12}")) errors.add("БИН или ИИН — 12 цифр.");
        return errors;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    @PostMapping("/{id:\\d+}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        try {
            if (!currentUserService.hasRole(Role.ADMIN)) throw new IllegalStateException("Удалить клиента может директор");
            clientService.delete(id);
            ok(ra, "Клиент удалён вместе с его заказами");
            return "redirect:/admin/clients";
        } catch (RuntimeException e) {
            fail(ra, e);
            return "redirect:/admin/clients/" + id;
        }
    }

    @PostMapping("/{id:\\d+}/prices")
    public String setPrice(@PathVariable Long id, @RequestParam Long product, @RequestParam String price, RedirectAttributes ra) {
        try {
            if (!currentUserService.hasRole(Role.ADMIN, Role.SALES, Role.ACCOUNTANT)) throw new IllegalStateException("Цены меняют продажи, бухгалтер и директор");
            BigDecimal p = num(price);
            if (p == null || p.signum() < 0) throw new IllegalArgumentException("Укажите цену");
            clientPriceService.upsert(id, product, p.setScale(2, RoundingMode.HALF_UP));
            orderPriceFillService.afterPriceChange(id, product);
            ok(ra, "Цена сохранена");
        } catch (RuntimeException e) {
            fail(ra, e);
        }
        return "redirect:/admin/clients/" + id + "#prices";
    }

    @PostMapping("/{id:\\d+}/prices/{product}/delete")
    public String deletePrice(@PathVariable Long id, @PathVariable Long product, RedirectAttributes ra) {
        try {
            clientPriceService.delete(id, product);
            ok(ra, "Индивидуальная цена убрана — действует цена прайса");
        } catch (RuntimeException e) {
            fail(ra, e);
        }
        return "redirect:/admin/clients/" + id + "#prices";
    }

    // ------------------------------------------------------------------ доступ на сайт, «Перезвоните», долги

    @GetMapping("/access")
    @Transactional(readOnly = true)
    public String access(Model model) {
        model.addAttribute("rows", currentUserService.hasRole(Role.ADMIN) ? shopAccessService.pending() : List.of());
        page(model, "Клиенты", "новые номера просят доступ к сайту", "clients");
        return "admin/clients/access";
    }

    @PostMapping("/access/{id}/{decision:approve|reject}")
    public String decide(@PathVariable Long id, @PathVariable String decision, RedirectAttributes ra) {
        try {
            if (!currentUserService.hasRole(Role.ADMIN)) throw new IllegalStateException("Пускать на сайт может директор");
            String who = currentUserService.username().orElse("admin");
            if (decision.equals("approve")) {
                ShopAccessRequest r = shopAccessService.approve(id, who);
                ok(ra, "Номер " + r.getPhone() + " допущен на сайт");
            } else {
                shopAccessService.reject(id, who);
                ok(ra, "Заявка отклонена");
            }
        } catch (RuntimeException e) {
            fail(ra, e);
        }
        return "redirect:/admin/clients/access";
    }

    @GetMapping("/callbacks")
    @Transactional(readOnly = true)
    public String callbacks(Model model) {
        model.addAttribute("rows", shopAccessService.callbacks());
        page(model, "Клиенты", "заявки «Перезвоните мне» с сайта", "clients");
        return "admin/clients/callbacks";
    }

    @PostMapping("/callbacks/{id}")
    public String callback(@PathVariable Long id, @RequestParam String status, @RequestParam(required = false) String note, RedirectAttributes ra) {
        try {
            shopAccessService.updateCallback(id, status, note, currentUserService.displayName());
            ok(ra, "Заявка обновлена");
        } catch (RuntimeException e) {
            fail(ra, e);
        }
        return "redirect:/admin/clients/callbacks";
    }

    @GetMapping("/debts")
    @Transactional(readOnly = true)
    public String debts(Model model) {
        List<Order> rows = orderService.findUnpaidDebts();
        model.addAttribute("rows", rows);
        model.addAttribute("sum", rows.stream().map(Order::getNetTotal).reduce(BigDecimal.ZERO, BigDecimal::add));
        model.addAttribute("canPay", currentUserService.hasRole(Role.ADMIN, Role.ACCOUNTANT));
        page(model, "Клиенты", "долги по выданным заказам", "clients");
        return "admin/clients/debts";
    }

    @PostMapping("/debts/{orderId}/paid")
    public String debtPaid(@PathVariable Long orderId, RedirectAttributes ra) {
        try {
            if (!currentUserService.hasRole(Role.ADMIN, Role.ACCOUNTANT)) throw new IllegalStateException("Оплату отмечает бухгалтер");
            orderService.markDebtPaid(orderId);
            ok(ra, "Долг по заказу № " + orderId + " погашен");
        } catch (RuntimeException e) {
            fail(ra, e);
        }
        return "redirect:/admin/clients/debts";
    }

    @PostMapping("/debts/{orderId}")
    public String setDebt(@PathVariable Long orderId, @RequestParam String due, RedirectAttributes ra) {
        try {
            if (!currentUserService.hasRole(Role.ADMIN, Role.ACCOUNTANT)) throw new IllegalStateException("Долг отмечает бухгалтер");
            orderService.setDebt(orderId, AdminSupport.date(due, null));
            ok(ra, "Долг по заказу № " + orderId + " записан");
        } catch (RuntimeException e) {
            fail(ra, e);
        }
        return "redirect:/admin/orders/" + orderId;
    }
}
