package com.confectionery.crm.admin;

import com.confectionery.crm.domain.*;
import com.confectionery.crm.repository.CompanyProfileRepository;
import com.confectionery.crm.repository.OrderRepository;
import com.confectionery.crm.repository.ProductRepository;
import com.confectionery.crm.repository.UserRepository;
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
import java.util.*;

import static com.confectionery.crm.admin.AdminSupport.*;

/**
 * Возврат внутри заказа, как заведено у «Юг Сладости»: дистрибьютор привозит собранный у магазинов возврат и забирает
 * новый заказ — возврат пишут сразу в этот заказ (что привезли, в штуках), его сумма вычитается из заказа, весь товар
 * негодный и сразу уходит в списание. Без приёмки, причин и статусов. Здесь: форма возврата, удаление, акт для печати.
 */
@Controller
@RequestMapping("/admin/orders/{orderId}/return")
@RequiredArgsConstructor
public class AdminOrderReturnController {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final CompanyProfileRepository companyProfileRepository;
    private final ProductReturnService returnService;
    private final ClientPriceService clientPriceService;
    private final CurrentUserService currentUserService;
    private final AdminFormat fmt;

    // ------------------------------------------------------------------ форма

    @GetMapping
    @Transactional(readOnly = true)
    public String form(@PathVariable Long orderId, @RequestParam(required = false) Long r, Model model, RedirectAttributes ra) {
        Order o = orderRepository.findById(orderId).orElse(null);
        if (o == null) {
            ra.addFlashAttribute("toast", "Заказа № " + orderId + " нет");
            ra.addFlashAttribute("toastError", true);
            return "redirect:/admin/orders";
        }
        ProductReturn ret = null;
        if (r != null) {
            ret = o.getReturns().stream().filter(x -> x.getId().equals(r)).findFirst().orElse(null);
            if (ret == null) {
                ra.addFlashAttribute("toast", "Возврата № " + r + " у заказа № " + orderId + " нет");
                ra.addFlashAttribute("toastError", true);
                return "redirect:/admin/orders/" + orderId;
            }
        } else if (!o.getReturns().isEmpty()) {
            ret = o.getReturns().get(0);                  // возврат к заказу один — открыть его
        }
        if (!ProductReturnService.acceptsReturn(o)) {
            ra.addFlashAttribute("toast", "К заказу «" + o.getStatus().badgeLabel() + "» возврат не прикрепить: клиент отдаёт возврат, когда забирает заказ");
            ra.addFlashAttribute("toastError", true);
            return "redirect:/admin/orders/" + orderId;
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        if (ret != null) {
            for (ProductReturnItem i : ret.getItems()) rows.add(row(i.getProduct().getId(), i.getQuantity().stripTrailingZeros().toPlainString()));
        }
        return form(o, ret, rows, List.of(), model);
    }

    private static Map<String, Object> row(Object key, String qty) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("qty", qty);
        return m;
    }

    private String form(Order o, ProductReturn ret, List<Map<String, Object>> rows, List<String> errors, Model model) {
        AdminOrders.Row row = AdminOrders.row(o);
        BigDecimal others = BigDecimal.ZERO;
        for (ProductReturn x : o.getReturns()) if (ret == null || !x.getId().equals(ret.getId())) others = others.add(x.getTotalSum());
        Map<String, Object> st = new LinkedHashMap<>();
        st.put("order", o.getId());
        st.put("ret", ret == null ? null : ret.getId());
        st.put("sum", o.getTotalPrice());
        st.put("others", others);
        st.put("paid", o.isPaid());
        // заказ оплатили раньше, чем приняли этот возврат, — переплату вернуть клиенту
        st.put("afterPay", o.isPaid() && (ret == null || o.getPaidAt() != null && ret.getCreatedAt().isAfter(o.getPaidAt())));
        st.put("canDelete", ret != null && currentUserService.hasRole(Role.ADMIN, Role.ACCOUNTANT));
        st.put("rows", rows);
        model.addAttribute("o", o);
        model.addAttribute("row", row);
        model.addAttribute("ret", ret);
        model.addAttribute("doneAt", AdminOrders.doneAt(o));
        model.addAttribute("canDelete", ret != null && currentUserService.hasRole(Role.ADMIN, Role.ACCOUNTANT));
        model.addAttribute("errors", errors);
        model.addAttribute("stateJson", AdminJson.of(st));
        model.addAttribute("errorsJson", AdminJson.of(errors));
        model.addAttribute("catalogJson", AdminJson.of(catalog(o, ret)));
        model.addAttribute("quickJson", AdminJson.of(quick(o)));
        page(model, "Заказы", "", "orders");
        return "admin/orders/return";
    }

    /** Товары для подсказок: цена — как ляжет в возврат (прежняя у строк возврата, цена в заказе, иначе цена клиента). */
    List<Map<String, Object>> catalog(Order o, ProductReturn ret) {
        Map<Long, BigDecimal> kept = new HashMap<>();
        Map<Long, Product> extra = new LinkedHashMap<>();
        if (ret != null) {
            for (ProductReturnItem i : ret.getItems()) {
                kept.putIfAbsent(i.getProduct().getId(), i.getPrice());
                extra.put(i.getProduct().getId(), i.getProduct());
            }
        }
        Map<Long, Product> all = new LinkedHashMap<>();
        for (Product p : productRepository.findByActiveTrue()) all.put(p.getId(), p);
        extra.forEach(all::putIfAbsent);            // снятый с продажи товар, который уже есть в возврате
        List<Map<String, Object>> out = new ArrayList<>();
        for (Product p : all.values()) {
            BigDecimal price = kept.get(p.getId());
            if (price == null) price = ProductReturnService.orderPrice(o, p.getId());
            if (price == null) price = clientPriceService.resolvePrice(o.getClient(), p);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", p.getId());
            m.put("label", p.getName());
            m.put("price", price);
            BigDecimal box = AdminOrders.boxUnits(p);
            m.put("box", box == null ? null : box.stripTrailingZeros().toPlainString());
            m.put("photo", AdminOrders.photo(p));
            out.add(m);
        }
        out.sort(Comparator.comparing(m -> m.get("label").toString(), String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    /** «Быстро добавить»: что клиент берёт — штук по его выданным заказам, больше всего сверху; нет выданных — товары этого заказа. */
    List<Long> quick(Order o) {
        Map<Long, BigDecimal> pcs = new HashMap<>();
        Map<Long, Boolean> active = new HashMap<>();
        for (Order x : orderRepository.findByClientIdOrderByCreatedAtDesc(o.getClient().getId())) {
            if (!x.getStatus().isShipped()) continue;
            for (OrderItem i : x.getItems()) {
                pcs.merge(i.getProduct().getId(), i.getQuantity(), BigDecimal::add);
                active.put(i.getProduct().getId(), i.getProduct().isActive());
            }
        }
        if (pcs.isEmpty()) {
            for (OrderItem i : o.getItems()) {
                pcs.merge(i.getProduct().getId(), i.getQuantity(), BigDecimal::add);
                active.put(i.getProduct().getId(), i.getProduct().isActive());
            }
        }
        return pcs.entrySet().stream().filter(e -> active.getOrDefault(e.getKey(), false))
                .sorted(Map.Entry.<Long, BigDecimal>comparingByValue().reversed())
                .limit(12).map(Map.Entry::getKey).toList();
    }

    // ------------------------------------------------------------------ сохранить, удалить

    @PostMapping
    public String save(@PathVariable Long orderId, HttpServletRequest req, Model model, RedirectAttributes ra) {
        Order o = orderRepository.findById(orderId).orElse(null);
        if (o == null) return "redirect:/admin/orders";
        Long rid = AdminSupport.id(req.getParameter("r"));
        ProductReturn ret = rid == null ? null : o.getReturns().stream().filter(x -> x.getId().equals(rid)).findFirst().orElse(null);
        String[] keys = arr(req, "lineProduct"), qtys = arr(req, "lineQty");
        List<Map<String, Object>> rows = new ArrayList<>();
        List<ProductReturnService.ItemInput> items = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        boolean bad = false;
        String dup = null;
        for (int i = 0; i < keys.length; i++) {
            String k = keys[i].trim(), q = at(qtys, i).trim();
            if (k.isEmpty() && q.isEmpty()) continue;
            Long pid = AdminSupport.id(k);
            rows.add(row(pid == null ? "" : pid, q));
            if (pid == null || !q.matches("\\d{1,5}") || Integer.parseInt(q) <= 0) {
                bad = true;
                continue;
            }
            Product p = productRepository.findById(pid).orElse(null);
            if (p == null) {
                bad = true;
                continue;
            }
            if (!seen.add(pid)) dup = p.getName();
            items.add(new ProductReturnService.ItemInput(pid, new BigDecimal(q), null));
        }
        if (items.isEmpty() && !bad) errors.add("Добавьте хотя бы один товар с количеством.");
        else if (bad) errors.add("Проверьте выделенные строки: товар из каталога и количество.");
        if (dup != null) errors.add("«" + dup + "» в двух строках — сложите количество в одну.");
        if (rid != null && ret == null) errors.add("Возврата № " + rid + " у этого заказа уже нет — обновите страницу.");
        if (errors.isEmpty()) {
            try {
                ProductReturn saved = returnService.saveForOrder(orderId, rid, items, currentUserService.username().orElse("admin"));
                ra.addFlashAttribute("toast", "Возврат № " + saved.getId() + ": " + fmt.num(ProductReturnService.pieces(saved)) + AdminFormat.NB
                        + "шт на " + fmt.money(saved.getTotalSum()) + " — вычтено из заказа № " + orderId);
                String next = req.getParameter("next");
                if (next != null && next.startsWith("/admin/")) return "redirect:" + next;
                return "redirect:/admin/orders/" + orderId + "#ret-" + saved.getId();
            } catch (RuntimeException e) {
                errors.add(e.getMessage() == null ? "Не получилось сохранить — попробуйте ещё раз." : e.getMessage());
            }
        }
        return form(o, ret, rows, errors, model);
    }

    @PostMapping("/{rid}/delete")
    public String delete(@PathVariable Long orderId, @PathVariable Long rid, RedirectAttributes ra) {
        try {
            if (!currentUserService.hasRole(Role.ADMIN, Role.ACCOUNTANT)) throw new IllegalStateException("Удалить возврат может администратор или бухгалтер");
            returnService.deleteForOrder(orderId, rid);
            ok(ra, "Возврат № " + rid + " удалён — сумма заказа № " + orderId + " снова без возврата");
            return "redirect:/admin/orders/" + orderId;
        } catch (RuntimeException e) {
            fail(ra, e);
            return "redirect:/admin/orders/" + orderId + "/return?r=" + rid;
        }
    }

    // ------------------------------------------------------------------ акт возврата

    public record ActLine(int n, Product product, BigDecimal qty, BigDecimal price, BigDecimal sum) {
    }

    /** Акт возврата для печати: A4, два экземпляра — для склада и для клиента. */
    @GetMapping("/{rid}/act")
    @Transactional(readOnly = true)
    public String act(@PathVariable Long orderId, @PathVariable Long rid, Model model, RedirectAttributes ra) {
        Order o = orderRepository.findById(orderId).orElse(null);
        ProductReturn ret = o == null ? null : o.getReturns().stream().filter(x -> x.getId().equals(rid)).findFirst().orElse(null);
        if (ret == null) {
            ra.addFlashAttribute("toast", "Возврата № " + rid + " у заказа № " + orderId + " нет");
            ra.addFlashAttribute("toastError", true);
            return "redirect:/admin/orders/" + orderId;
        }
        List<ActLine> lines = new ArrayList<>();
        int n = 1;
        for (ProductReturnItem i : ret.getItems()) lines.add(new ActLine(n++, i.getProduct(), i.getQuantity(), i.getPrice(), i.getPrice().multiply(i.getQuantity())));
        AdminOrders.Totals t = AdminOrders.totals(o);
        CompanyProfile company = companyProfileRepository.findAll().stream().findFirst().orElse(null);
        model.addAttribute("o", o);
        model.addAttribute("r", ret);
        model.addAttribute("lines", lines);
        model.addAttribute("pcs", ProductReturnService.pieces(ret));
        model.addAttribute("t", t);
        model.addAttribute("doneAt", AdminOrders.doneAt(o));
        model.addAttribute("company", company == null || company.getName() == null || company.getName().isBlank() ? "«Юг Сладости»" : company.getName());
        model.addAttribute("companyInfo", company == null ? null : firstFilled(company.getLegalInfo(), company.getAddress()));
        model.addAttribute("by", userName(ret.getCreatedBy()));
        model.addAttribute("copies", List.of("Экземпляр склада", "Экземпляр клиента"));
        return "admin/orders/return-act";
    }

    /** Кто оформил: ФИО сотрудника по логину, иначе как записано. */
    String userName(String login) {
        if (login == null) return "";
        return userRepository.findByUsername(login).map(u -> u.getFullName() == null || u.getFullName().isBlank() ? login : u.getFullName()).orElse(login);
    }

    private static String firstFilled(String... v) {
        for (String s : v) if (s != null && !s.isBlank()) return s.trim();
        return null;
    }
}
