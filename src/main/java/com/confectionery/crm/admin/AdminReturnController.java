package com.confectionery.crm.admin;

import com.confectionery.crm.domain.*;
import com.confectionery.crm.repository.ClientRepository;
import com.confectionery.crm.repository.OrderRepository;
import com.confectionery.crm.repository.ProductRepository;
import com.confectionery.crm.service.ClientPriceService;
import com.confectionery.crm.service.CurrentUserService;
import com.confectionery.crm.service.ProductReturnService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static com.confectionery.crm.admin.AdminSupport.*;

/**
 * Возвраты испорченного товара: список, карточка, оформление (в том числе из выданного заказа —
 * «Оформить возврат»), изменение, удаление. Через ProductReturnService: 1С-документ на склад
 * «Испорченные товары», закрепление за заказом — как в приложении.
 */
@Controller
@RequestMapping("/admin/returns")
@RequiredArgsConstructor
public class AdminReturnController {

    private final ProductReturnService returnService;
    private final ClientRepository clientRepository;
    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final ClientPriceService clientPriceService;
    private final CurrentUserService currentUserService;

    static final List<String> REASONS = List.of("Брак", "Истёк срок годности", "Пересорт", "Повреждена упаковка", "Другая причина");

    @GetMapping
    @Transactional(readOnly = true)
    public String list(@RequestParam(defaultValue = "") String q, @RequestParam(required = false) String from,
                       @RequestParam(required = false) String to, Model model) {
        LocalDate f = date(from, LocalDate.now().minusDays(30)), t = date(to, LocalDate.now());
        String nq = AdminOrderController.norm(q);
        List<ProductReturn> rows = returnService.findByDateRange(f, t).stream()
                .filter(r -> nq.isEmpty() || AdminOrderController.norm(r.getId() + " " + r.getClient().getName() + " "
                        + (r.getReason() == null ? "" : r.getReason()) + " " + (r.getOrder() == null ? "" : r.getOrder().getId())).contains(nq))
                .toList();
        BigDecimal sum = rows.stream().map(ProductReturn::getTotalSum).reduce(BigDecimal.ZERO, BigDecimal::add);
        model.addAttribute("rows", rows);
        model.addAttribute("sum", sum);
        model.addAttribute("q", q);
        model.addAttribute("from", f);
        model.addAttribute("to", t);
        page(model, "Возвраты", "испорченный и непроданный товар от клиентов", "returns");
        return "admin/returns/list";
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public String view(@PathVariable Long id, Model model, RedirectAttributes ra) {
        ProductReturn r;
        try {
            r = returnService.findById(id);
        } catch (IllegalArgumentException e) {
            fail(ra, e);
            return "redirect:/admin/returns";
        }
        model.addAttribute("r", r);
        model.addAttribute("canDelete", currentUserService.hasRole(Role.ADMIN, Role.ACCOUNTANT));
        page(model, "Возвраты", "", "returns");
        return "admin/returns/view";
    }

    public record FormLine(Long productId, String qty, String price) {
    }

    @GetMapping("/new")
    @Transactional(readOnly = true)
    public String newForm(@RequestParam(required = false) Long order, @RequestParam(required = false) Long client, Model model) {
        Order o = order == null ? null : orderRepository.findById(order).orElse(null);
        List<FormLine> lines = new ArrayList<>();
        if (o != null) {
            for (OrderItem i : o.getItems()) lines.add(new FormLine(i.getProduct().getId(), "", plain(i.getPrice())));
        }
        return form(null, o != null ? o.getClient().getId() : client, o == null ? null : o.getId(), LocalDate.now(),
                REASONS.get(0), "", lines, List.of(), model);
    }

    @GetMapping("/{id}/edit")
    @Transactional(readOnly = true)
    public String editForm(@PathVariable Long id, Model model) {
        ProductReturn r = returnService.findById(id);
        List<FormLine> lines = r.getItems().stream()
                .map(i -> new FormLine(i.getProduct().getId(), plain(i.getQuantity()), plain(i.getPrice()))).toList();
        String reason = r.getReason() == null ? "" : r.getReason();
        String preset = REASONS.contains(reason) ? reason : "Другая причина";
        return form(id, r.getClient().getId(), r.getOrder() == null ? null : r.getOrder().getId(), r.getReturnDate(),
                preset, REASONS.contains(reason) ? "" : reason, lines, List.of(), model);
    }

    private static String plain(BigDecimal v) {
        return v == null ? "" : v.stripTrailingZeros().toPlainString();
    }

    private String form(Long id, Long clientId, Long orderId, LocalDate date, String reason, String reasonText,
                        List<FormLine> lines, List<String> errors, Model model) {
        Client client = clientId == null ? null : clientRepository.findById(clientId).orElse(null);
        List<Product> products = new ArrayList<>(productRepository.findByActiveTrue());
        products.sort(Comparator.comparing(Product::getName, String.CASE_INSENSITIVE_ORDER));
        Map<Long, BigDecimal> prices = new HashMap<>();
        if (client != null) for (Product p : products) prices.put(p.getId(), clientPriceService.resolvePrice(client, p));
        List<Client> clients = new ArrayList<>(clientRepository.findAll());
        clients.sort(Comparator.comparing(Client::getName, String.CASE_INSENSITIVE_ORDER));
        model.addAttribute("id", id);
        model.addAttribute("clients", clients);
        model.addAttribute("clientId", clientId);
        model.addAttribute("orderId", orderId);
        model.addAttribute("orders", clientId == null ? List.of() : orderRepository.findByClientIdOrderByCreatedAtDesc(clientId).stream()
                .filter(o -> o.getStatus().isShipped()).limit(30).toList());
        model.addAttribute("date", date);
        model.addAttribute("reasons", REASONS);
        model.addAttribute("reason", reason);
        model.addAttribute("reasonText", reasonText);
        model.addAttribute("lines", lines);
        model.addAttribute("products", products);
        model.addAttribute("prices", prices);
        model.addAttribute("errors", errors);
        page(model, "Возвраты", "", "returns");
        return "admin/returns/form";
    }

    @PostMapping
    public String create(HttpServletRequest req, Model model, RedirectAttributes ra) {
        return save(null, req, model, ra);
    }

    @PostMapping("/{id}")
    public String update(@PathVariable Long id, HttpServletRequest req, Model model, RedirectAttributes ra) {
        return save(id, req, model, ra);
    }

    private String save(Long id, HttpServletRequest req, Model model, RedirectAttributes ra) {
        Long clientId = AdminSupport.id(req.getParameter("client"));
        Long orderId = AdminSupport.id(req.getParameter("order"));
        LocalDate date = AdminSupport.date(req.getParameter("date"), LocalDate.now());
        String reason = Optional.ofNullable(req.getParameter("reason")).orElse("");
        String reasonText = Optional.ofNullable(req.getParameter("reasonText")).orElse("").trim();
        String fullReason = reason.equals("Другая причина") || reason.isBlank() ? reasonText : reason + (reasonText.isEmpty() ? "" : ": " + reasonText);
        String[] ps = arr(req, "product"), qs = arr(req, "qty"), prs = arr(req, "price");
        List<FormLine> lines = new ArrayList<>();
        List<ProductReturnService.ItemInput> items = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        boolean bad = false;
        for (int i = 0; i < ps.length; i++) {
            Long pid = AdminSupport.id(ps[i]);
            BigDecimal q = num(at(qs, i)), pr = num(at(prs, i));
            lines.add(new FormLine(pid, at(qs, i), at(prs, i)));
            if (q == null || q.signum() == 0) continue;   // строки без количества (не вернули) не считаются
            if (pid == null || q == null || q.signum() <= 0 || pr == null || pr.signum() < 0) {
                bad = true;
                continue;
            }
            items.add(new ProductReturnService.ItemInput(pid, q, pr));
        }
        if (clientId == null) errors.add("Выберите клиента.");
        if (bad) errors.add("Проверьте строки: товар, количество больше нуля и цена.");
        else if (items.isEmpty()) errors.add("Добавьте хотя бы один товар.");
        if (fullReason.isBlank()) errors.add("Укажите причину возврата.");
        if (errors.isEmpty()) {
            try {
                ProductReturn r;
                if (id == null) {
                    r = returnService.create(clientId, items, date, fullReason, orderId,
                            currentUserService.username().orElse("admin"));
                    ok(ra, "Возврат № " + r.getId() + " оформлен");
                } else {
                    r = returnService.update(id, clientId, items, date, fullReason);
                    Long current = r.getOrder() == null ? null : r.getOrder().getId();
                    if (!Objects.equals(current, orderId)) {
                        r = orderId == null ? returnService.detach(id) : returnService.attach(id, orderId);
                    }
                    ok(ra, "Возврат № " + r.getId() + " сохранён");
                }
                return "redirect:/admin/returns/" + r.getId();
            } catch (RuntimeException e) {
                errors.add(e.getMessage());
            }
        }
        return form(id, clientId, orderId, date, reason, reasonText, lines, errors, model);
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        try {
            if (!currentUserService.hasRole(Role.ADMIN, Role.ACCOUNTANT)) throw new IllegalStateException("Удалить возврат может директор или бухгалтер");
            returnService.delete(id);
            ok(ra, "Возврат № " + id + " удалён");
            return "redirect:/admin/returns";
        } catch (RuntimeException e) {
            fail(ra, e);
            return "redirect:/admin/returns/" + id;
        }
    }

    @PostMapping("/{id}/sync-1c")
    public String resync(@PathVariable Long id, RedirectAttributes ra) {
        try {
            returnService.resyncOneC(id);
            ok(ra, "Возврат № " + id + " поставлен в очередь на отправку в 1С");
        } catch (RuntimeException e) {
            fail(ra, e);
        }
        return "redirect:/admin/returns/" + id;
    }
}
