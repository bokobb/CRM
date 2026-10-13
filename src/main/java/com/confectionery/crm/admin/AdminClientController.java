package com.confectionery.crm.admin;

import com.confectionery.crm.domain.*;
import com.confectionery.crm.repository.OrderRepository;
import com.confectionery.crm.repository.ProductRepository;
import com.confectionery.crm.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

import static com.confectionery.crm.admin.AdminSupport.*;

/**
 * Клиенты: список, карточка (контакты, БИН, способ оплаты по умолчанию, заказы и возвраты, долг,
 * что берёт чаще всего, индивидуальные цены), заявки на доступ к сайту по телефону, заявки
 * «Перезвоните мне» и долги по счетам.
 */
@Controller
@RequestMapping("/admin/clients")
@RequiredArgsConstructor
public class AdminClientController {

    private final ClientService clientService;
    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final ClientPriceService clientPriceService;
    private final ProductReturnService returnService;
    private final ShopAccessService shopAccessService;
    private final OrderService orderService;
    private final OrderPriceFillService orderPriceFillService;
    private final CurrentUserService currentUserService;

    public record Row(Client client, int orders, BigDecimal sales, LocalDate last, BigDecimal debt) {
    }

    @GetMapping
    @Transactional(readOnly = true)
    public String list(@RequestParam(defaultValue = "") String q, Model model) {
        String nq = AdminOrderController.norm(q);
        String digits = nq.replaceAll("\\D", "");
        Map<Long, List<Order>> byClient = new HashMap<>();
        for (Order o : orderRepository.findAll()) byClient.computeIfAbsent(o.getClient().getId(), k -> new ArrayList<>()).add(o);
        List<Row> rows = new ArrayList<>();
        for (Client c : clientService.findAll()) {
            String hay = AdminOrderController.norm(c.getName() + " " + (c.getContactPerson() == null ? "" : c.getContactPerson()) + " " + (c.getAddress() == null ? "" : c.getAddress()));
            boolean phone = digits.length() >= 3 && c.getPhone() != null && c.getPhone().replaceAll("\\D", "").contains(digits);
            if (!nq.isEmpty() && !hay.contains(nq) && !phone) continue;
            List<Order> os = byClient.getOrDefault(c.getId(), List.of());
            BigDecimal sales = BigDecimal.ZERO, debt = BigDecimal.ZERO;
            LocalDate last = null;
            for (Order o : os) {
                if (o.getStatus().isShipped()) sales = sales.add(o.getNetTotal());
                if (o.getDebtDueDate() != null && !Boolean.TRUE.equals(o.getDebtPaid()) && o.getStatus() != OrderStatus.CANCELLED) debt = debt.add(o.getNetTotal());
                LocalDate d = o.getCreatedAt().toLocalDate();
                if (last == null || d.isAfter(last)) last = d;
            }
            rows.add(new Row(c, os.size(), sales, last, debt));
        }
        rows.sort(Comparator.comparing(Row::sales).reversed());
        model.addAttribute("rows", rows);
        model.addAttribute("q", q);
        model.addAttribute("isAdmin", currentUserService.hasRole(Role.ADMIN));
        model.addAttribute("accessCount", currentUserService.hasRole(Role.ADMIN) ? shopAccessService.pending().size() : 0);
        model.addAttribute("callbackCount", shopAccessService.callbacks().stream().filter(c -> "NEW".equals(c.getStatus())).count());
        model.addAttribute("debtCount", orderService.findUnpaidDebts().size());
        page(model, "Клиенты", "магазины и покупатели, их заказы, цены и долги", "clients");
        return "admin/clients/list";
    }

    public record Top(Product product, BigDecimal qty) {
    }

    @GetMapping("/{id:\\d+}")
    @Transactional(readOnly = true)
    public String view(@PathVariable Long id, Model model) {
        Client c = clientService.findById(id);
        List<Order> orders = orderRepository.findByClientIdOrderByCreatedAtDesc(id);
        Map<Product, BigDecimal> qty = new HashMap<>();
        BigDecimal sales = BigDecimal.ZERO, month = BigDecimal.ZERO, debt = BigDecimal.ZERO;
        LocalDate monthStart = LocalDate.now().withDayOfMonth(1);
        for (Order o : orders) {
            if (o.getStatus().isShipped()) {
                sales = sales.add(o.getNetTotal());
                if (o.getShippedAt() != null && !o.getShippedAt().toLocalDate().isBefore(monthStart)) month = month.add(o.getNetTotal());
                o.getItems().forEach(i -> qty.merge(i.getProduct(), i.getQuantity(), BigDecimal::add));
            }
            if (o.getDebtDueDate() != null && !Boolean.TRUE.equals(o.getDebtPaid()) && o.getStatus() != OrderStatus.CANCELLED) debt = debt.add(o.getNetTotal());
        }
        List<Top> top = qty.entrySet().stream().sorted(Map.Entry.<Product, BigDecimal>comparingByValue().reversed())
                .limit(7).map(e -> new Top(e.getKey(), e.getValue())).toList();
        model.addAttribute("c", c);
        model.addAttribute("orders", orders.stream().limit(15).map(AdminOrders::row).toList());
        model.addAttribute("orderCount", orders.size());
        model.addAttribute("returns", returnService.findAll().stream().filter(r -> r.getClient().getId().equals(id)).limit(10).toList());
        model.addAttribute("sales", sales);
        model.addAttribute("month", month);
        model.addAttribute("debt", debt);
        model.addAttribute("top", top);
        model.addAttribute("prices", clientPriceService.findByClient(id));
        model.addAttribute("canPrice", currentUserService.hasRole(Role.ADMIN, Role.SALES, Role.ACCOUNTANT));
        model.addAttribute("isAdmin", currentUserService.hasRole(Role.ADMIN));
        model.addAttribute("products", productRepository.findByActiveTrue().stream()
                .sorted(Comparator.comparing(Product::getName, String.CASE_INSENSITIVE_ORDER)).toList());
        page(model, "Клиенты", c.getName(), "clients");
        return "admin/clients/view";
    }

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
        c.setName(f.getOrDefault("name", "").trim());
        c.setContactPerson(blank(f.get("contactPerson")));
        c.setPhone(blank(f.get("phone")));
        c.setEmail(blank(f.get("email")));
        c.setAddress(blank(f.get("address")));
        c.setBin(blank(f.get("bin")));
        c.setNotes(blank(f.get("notes")));
        String pay = f.get("defaultPayment");
        c.setDefaultPayment(pay == null || pay.isBlank() ? null : PaymentMethod.valueOf(pay));
        List<String> errors = new ArrayList<>();
        if (c.getName().isEmpty()) errors.add("Укажите название магазина или имя покупателя.");
        if (c.getBin() != null && !c.getBin().matches("\\d{12}")) errors.add("БИН/ИИН — 12 цифр.");
        if (errors.isEmpty()) {
            try {
                Client saved = clientService.save(c);
                ok(ra, "Клиент «" + saved.getName() + "» сохранён");
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
