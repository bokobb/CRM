package com.confectionery.crm.admin;

import com.confectionery.crm.domain.*;
import com.confectionery.crm.repository.ProductRepository;
import com.confectionery.crm.service.*;
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
 * Склад готовой продукции (/admin/stock): остатки, журнал поступлений и списаний, документы
 * «Поступление»/«Списание» (StockService — уходят в 1С проведёнными, как из приложения). Склад сырья —
 * AdminMaterialController.
 */
@Controller
@RequiredArgsConstructor
public class AdminWarehouseController {

    private final IngredientService ingredientService;
    private final ProductRepository productRepository;
    private final StockService stockService;
    private final CurrentUserService currentUserService;
    private final OneCStockSyncService oneCStockSyncService;

    // ------------------------------------------------------------------ склад готовой продукции

    @GetMapping("/admin/stock")
    @Transactional(readOnly = true)
    public String stock(@RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "0") int pf, Model model) {
        String nq = AdminOrderController.norm(q);
        List<Product> rows = productRepository.findByActiveTrue().stream()
                .filter(p -> pf == 1 ? p.isSemiFinished() : !p.isSemiFinished())
                .filter(p -> nq.isEmpty() || AdminOrderController.norm(p.getName()).contains(nq))
                .sorted(Comparator.comparing(Product::getName, String.CASE_INSENSITIVE_ORDER)).toList();
        BigDecimal value = rows.stream().filter(p -> p.getQuantityInStock() != null && p.getQuantityInStock().signum() > 0)
                .map(p -> p.getQuantityInStock().multiply(p.getPrice())).reduce(BigDecimal.ZERO, BigDecimal::add);
        model.addAttribute("rows", rows);
        model.addAttribute("q", q);
        model.addAttribute("pf", pf == 1);
        model.addAttribute("value", value);
        model.addAttribute("canEdit", currentUserService.hasRole(Role.ADMIN, Role.WAREHOUSE_FG, Role.ACCOUNTANT));
        model.addAttribute("isAdmin", currentUserService.hasRole(Role.ADMIN));
        page(model, "Склад", "готовая продукция и полуфабрикаты", "stock");
        return "admin/warehouse/stock";
    }

    @PostMapping("/admin/stock/sync-1c")
    public String syncStock(RedirectAttributes ra) {
        try {
            if (!currentUserService.hasRole(Role.ADMIN)) throw new IllegalStateException("Синхронизацию запускает администратор");
            oneCStockSyncService.sync();
            ok(ra, "Остатки обновлены из 1С");
        } catch (RuntimeException e) {
            fail(ra, e);
        }
        return "redirect:/admin/stock";
    }

    // ------------------------------------------------------------------ журнал и документы (оба склада)

    private static boolean isProduct(String sec) {
        return sec.equals("stock");
    }

    @GetMapping("/admin/{sec:stock}/movements")
    @Transactional(readOnly = true)
    public String movements(@PathVariable String sec, @RequestParam(defaultValue = "all") String type,
                            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
                            @RequestParam(defaultValue = "") String q, Model model) {
        LocalDate f = date(from, LocalDate.now().minusDays(7)), t = date(to, LocalDate.now());
        StockMovementType tp = type.equals("in") ? StockMovementType.RECEIPT : type.equals("out") ? StockMovementType.WRITE_OFF : null;
        boolean product = isProduct(sec);
        List<StockMovement> rows = stockService.findMovements(tp, f.atStartOfDay(), t.plusDays(1).atStartOfDay(), q).stream()
                .filter(m -> product == (m.getProduct() != null)).toList();
        model.addAttribute("rows", rows);
        model.addAttribute("type", type);
        model.addAttribute("from", f);
        model.addAttribute("to", t);
        model.addAttribute("q", q);
        model.addAttribute("base", "/admin/" + sec);
        model.addAttribute("product", product);
        model.addAttribute("isAdmin", currentUserService.hasRole(Role.ADMIN));
        page(model, product ? "Склад" : "Склад сырья", "поступления и списания", sec);
        return "admin/warehouse/movements";
    }

    @PostMapping("/admin/{sec:stock}/movements/{id}/delete")
    public String deleteMovement(@PathVariable String sec, @PathVariable Long id, RedirectAttributes ra) {
        try {
            if (!currentUserService.hasRole(Role.ADMIN)) throw new IllegalStateException("Удалить движение может администратор");
            stockService.deleteMovement(id);
            ok(ra, "Движение удалено, остаток пересчитан");
        } catch (RuntimeException e) {
            fail(ra, e);
        }
        return "redirect:/admin/" + sec + "/movements";
    }

    public record DocLine(Long itemId, String qty) {
    }

    @GetMapping("/admin/{sec:stock}/doc")
    @Transactional(readOnly = true)
    public String docForm(@PathVariable String sec, @RequestParam(defaultValue = "in") String type,
                          @RequestParam(required = false) Long item, Model model) {
        List<DocLine> lines = new ArrayList<>();
        if (item != null) lines.add(new DocLine(item, ""));
        return doc(sec, type, LocalDate.now(), "", lines, List.of(), model);
    }

    private String doc(String sec, String type, LocalDate date, String reason, List<DocLine> lines, List<String> errors, Model model) {
        boolean product = isProduct(sec);
        model.addAttribute("sec", sec);
        model.addAttribute("product", product);
        model.addAttribute("type", type.equals("out") ? "out" : "in");
        model.addAttribute("date", date);
        model.addAttribute("reason", reason);
        model.addAttribute("lines", lines);
        model.addAttribute("errors", errors);
        model.addAttribute("base", "/admin/" + sec);
        if (product) {
            model.addAttribute("items", productRepository.findByActiveTrue().stream()
                    .sorted(Comparator.comparing(Product::getName, String.CASE_INSENSITIVE_ORDER))
                    .map(p -> new Item(p.getId(), p.getName(), p.getUnit() == null || p.getUnit().isBlank() ? "шт" : p.getUnit(), p.getQuantityInStock())).toList());
        } else {
            model.addAttribute("items", ingredientService.findAllActive().stream()
                    .sorted(Comparator.comparing(Ingredient::getName, String.CASE_INSENSITIVE_ORDER))
                    .map(i -> new Item(i.getId(), i.getName(), i.getUnit(), i.getQuantityInStock())).toList());
        }
        page(model, product ? "Склад" : "Склад сырья", "", sec);
        model.addAttribute("sec", sec);
        return "admin/warehouse/doc";
    }

    public record Item(Long id, String name, String unit, BigDecimal stock) {
    }

    @PostMapping("/admin/{sec:stock}/doc")
    public String saveDoc(@PathVariable String sec, HttpServletRequest req, Model model, RedirectAttributes ra) {
        boolean product = isProduct(sec);
        String type = "out".equals(req.getParameter("type")) ? "out" : "in";
        LocalDate date = date(req.getParameter("date"), LocalDate.now());
        String reason = Optional.ofNullable(req.getParameter("reason")).orElse("").trim();
        String[] ids = arr(req, "item"), qs = arr(req, "qty");
        List<DocLine> lines = new ArrayList<>();
        List<StockService.DocumentLine> doc = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        boolean bad = false;
        for (int i = 0; i < ids.length; i++) {
            Long id = AdminSupport.id(ids[i]);
            BigDecimal q = num(at(qs, i));
            if (id == null && q == null) continue;
            lines.add(new DocLine(id, at(qs, i)));
            if (id == null || q == null || q.signum() <= 0) {
                bad = true;
                continue;
            }
            if (product) {
                doc.add(new StockService.DocumentLine(null, productRepository.findById(id).orElseThrow(), q));
            } else {
                doc.add(new StockService.DocumentLine(ingredientService.findById(id), null, q));
            }
        }
        if (bad) errors.add("Проверьте строки: позиция и количество больше нуля.");
        else if (doc.isEmpty()) errors.add("Добавьте хотя бы одну позицию.");
        if (type.equals("out") && reason.isEmpty()) errors.add("Укажите причину списания.");
        if (errors.isEmpty()) {
            try {
                stockService.createDocument(type.equals("out") ? StockMovementType.WRITE_OFF : StockMovementType.RECEIPT, product,
                        date, reason, doc, currentUserService.username().orElse("admin"));
                ok(ra, (type.equals("out") ? "Списание" : "Поступление") + " проведено: " + doc.size() + " "
                        + new AdminFormat().plural(doc.size(), "позиция", "позиции", "позиций") + ". Документ уйдёт в 1С.");
                return "redirect:/admin/" + sec + "/movements";
            } catch (RuntimeException e) {
                errors.add(e.getMessage());
            }
        }
        return doc(sec, type, date, reason, lines, errors, model);
    }
}
