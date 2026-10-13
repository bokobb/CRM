package com.confectionery.crm.admin;

import com.confectionery.crm.domain.*;
import com.confectionery.crm.service.*;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static com.confectionery.crm.admin.AdminSupport.*;

/**
 * Покупки сырья у поставщиков: список, карточка, новая и изменение (PurchaseService — приход на склад
 * сырья и «Поступление товаров» в 1С), фото накладной, поставщики (контрагенты из 1С).
 */
@Controller
@RequestMapping("/admin/purchases")
@RequiredArgsConstructor
public class AdminPurchaseController {

    private final PurchaseService purchaseService;
    private final CounterpartyService counterpartyService;
    private final IngredientService ingredientService;
    private final CurrentUserService currentUserService;

    @GetMapping
    @Transactional(readOnly = true)
    public String list(@RequestParam(defaultValue = "") String q, @RequestParam(required = false) String from,
                       @RequestParam(required = false) String to, Model model) {
        LocalDate f = date(from, LocalDate.now().minusDays(30)), t = date(to, LocalDate.now());
        String nq = AdminOrderController.norm(q);
        List<Purchase> rows = purchaseService.findByPurchaseDateBetween(f, t).stream()
                .filter(p -> nq.isEmpty() || AdminOrderController.norm(p.getId() + " " + p.getCounterparty().getName() + " "
                        + p.getItems().stream().map(i -> i.getIngredient().getName()).reduce("", (a, b) -> a + " " + b)).contains(nq))
                .sorted(Comparator.comparing(Purchase::getPurchaseDate).thenComparing(Purchase::getId).reversed())
                .toList();
        model.addAttribute("rows", rows);
        model.addAttribute("sum", rows.stream().map(Purchase::getTotalSum).reduce(BigDecimal.ZERO, BigDecimal::add));
        model.addAttribute("q", q);
        model.addAttribute("from", f);
        model.addAttribute("to", t);
        model.addAttribute("canEdit", canEdit());
        page(model, "Покупки", "закупки сырья у поставщиков", "purchases");
        return "admin/purchases/list";
    }

    private boolean canEdit() {
        return currentUserService.hasRole(Role.ADMIN, Role.WAREHOUSE_RAW);
    }

    @GetMapping("/{id:\\d+}")
    @Transactional(readOnly = true)
    public String view(@PathVariable Long id, Model model) {
        model.addAttribute("p", purchaseService.findById(id));
        model.addAttribute("canEdit", canEdit());
        model.addAttribute("canSync", currentUserService.hasRole(Role.ADMIN, Role.ACCOUNTANT));
        page(model, "Покупки", "", "purchases");
        return "admin/purchases/view";
    }

    public record FormLine(Long ingredientId, String qty, String total) {
    }

    @GetMapping("/new")
    @Transactional(readOnly = true)
    public String newForm(@RequestParam(required = false) Long ingredient, Model model) {
        List<FormLine> lines = new ArrayList<>();
        if (ingredient != null) lines.add(new FormLine(ingredient, "", ""));
        return form(null, null, LocalDate.now(), lines, List.of(), model);
    }

    @GetMapping("/{id:\\d+}/edit")
    @Transactional(readOnly = true)
    public String editForm(@PathVariable Long id, Model model) {
        Purchase p = purchaseService.findById(id);
        List<FormLine> lines = p.getItems().stream().map(i -> new FormLine(i.getIngredient().getId(),
                i.getQuantity().stripTrailingZeros().toPlainString(),
                i.getTotalPrice() == null ? "" : i.getTotalPrice().stripTrailingZeros().toPlainString())).toList();
        return form(id, p.getCounterparty().getId(), p.getPurchaseDate(), lines, List.of(), model);
    }

    private String form(Long id, Long counterpartyId, LocalDate date, List<FormLine> lines, List<String> errors, Model model) {
        List<Counterparty> cps = new ArrayList<>(counterpartyService.findAll());
        cps.sort(Comparator.comparing(Counterparty::getName, String.CASE_INSENSITIVE_ORDER));
        List<Ingredient> ings = new ArrayList<>(ingredientService.findAllActive());
        ings.sort(Comparator.comparing(Ingredient::getName, String.CASE_INSENSITIVE_ORDER));
        model.addAttribute("id", id);
        model.addAttribute("counterparties", cps);
        model.addAttribute("counterpartyId", counterpartyId);
        model.addAttribute("date", date);
        model.addAttribute("lines", lines);
        model.addAttribute("ingredients", ings);
        model.addAttribute("errors", errors);
        page(model, "Покупки", "", "purchases");
        return "admin/purchases/form";
    }

    @PostMapping
    public String create(HttpServletRequest req, Model model, RedirectAttributes ra) {
        return save(null, req, model, ra);
    }

    @PostMapping("/{id:\\d+}")
    public String update(@PathVariable Long id, HttpServletRequest req, Model model, RedirectAttributes ra) {
        return save(id, req, model, ra);
    }

    private String save(Long id, HttpServletRequest req, Model model, RedirectAttributes ra) {
        Long cp = AdminSupport.id(req.getParameter("counterparty"));
        String newCp = Optional.ofNullable(req.getParameter("newCounterparty")).orElse("").trim();
        LocalDate date = AdminSupport.date(req.getParameter("date"), LocalDate.now());
        String[] is = arr(req, "ingredient"), qs = arr(req, "qty"), ts = arr(req, "total");
        List<FormLine> lines = new ArrayList<>();
        List<PurchaseService.ItemInput> items = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        boolean bad = false;
        for (int i = 0; i < is.length; i++) {
            Long ing = AdminSupport.id(is[i]);
            BigDecimal q = num(at(qs, i)), t = num(at(ts, i));
            if (ing == null && q == null && t == null) continue;
            lines.add(new FormLine(ing, at(qs, i), at(ts, i)));
            if (ing == null || q == null || q.signum() <= 0 || (t != null && t.signum() < 0)) {
                bad = true;
                continue;
            }
            items.add(new PurchaseService.ItemInput(ing, q, t));
        }
        if (cp == null && newCp.isEmpty()) errors.add("Выберите поставщика.");
        if (bad) errors.add("Проверьте строки: сырьё и количество больше нуля.");
        else if (items.isEmpty()) errors.add("Добавьте хотя бы одну позицию.");
        if (errors.isEmpty()) {
            try {
                if (cp == null) cp = counterpartyService.create(newCp).getId();
                String by = currentUserService.username().orElse("admin");
                Purchase p = id == null ? purchaseService.create(cp, items, date, by) : purchaseService.update(id, cp, items, date, by);
                ok(ra, "Покупка № " + p.getId() + (id == null ? " оформлена, сырьё пришло на склад" : " сохранена"));
                return "redirect:/admin/purchases/" + p.getId();
            } catch (RuntimeException e) {
                errors.add(e.getMessage());
            }
        }
        return form(id, cp, date, lines, errors, model);
    }

    @PostMapping("/{id:\\d+}/image")
    public String image(@PathVariable Long id, @RequestParam("file") MultipartFile file, RedirectAttributes ra) {
        try {
            purchaseService.attachImage(id, file);
            ok(ra, "Фото накладной сохранено");
        } catch (RuntimeException e) {
            fail(ra, e);
        }
        return "redirect:/admin/purchases/" + id;
    }

    @PostMapping("/{id:\\d+}/sync-1c")
    public String resync(@PathVariable Long id, RedirectAttributes ra) {
        try {
            purchaseService.resyncOneC(id);
            ok(ra, "Покупка № " + id + " отправлена в 1С ещё раз");
        } catch (RuntimeException e) {
            fail(ra, e);
        }
        return "redirect:/admin/purchases/" + id;
    }

    // ------------------------------------------------------------------ поставщики

    @GetMapping("/suppliers")
    @Transactional(readOnly = true)
    public String suppliers(Model model) {
        List<Counterparty> cps = new ArrayList<>(counterpartyService.findAll());
        cps.sort(Comparator.comparing(Counterparty::getName, String.CASE_INSENSITIVE_ORDER));
        model.addAttribute("rows", cps);
        model.addAttribute("canEdit", canEdit());
        model.addAttribute("isAdmin", currentUserService.hasRole(Role.ADMIN));
        page(model, "Покупки", "поставщики", "purchases");
        return "admin/purchases/suppliers";
    }

    @PostMapping("/suppliers")
    public String addSupplier(@RequestParam String name, RedirectAttributes ra) {
        try {
            counterpartyService.create(name);
            ok(ra, "Поставщик «" + name.trim() + "» добавлен");
        } catch (RuntimeException e) {
            fail(ra, e);
        }
        return "redirect:/admin/purchases/suppliers";
    }

    @PostMapping("/suppliers/{id}/delete")
    public String deleteSupplier(@PathVariable Long id, RedirectAttributes ra) {
        try {
            counterpartyService.delete(id);
            ok(ra, "Поставщик удалён");
        } catch (RuntimeException e) {
            fail(ra, e);
        }
        return "redirect:/admin/purchases/suppliers";
    }

    @PostMapping("/suppliers/sync-1c")
    public String syncSuppliers(RedirectAttributes ra) {
        try {
            CounterpartyService.SyncResult r = counterpartyService.syncFromOneC();
            ok(ra, "Из 1С: добавлено " + r.created() + ", обновлено " + r.updated() + ", удалено " + r.deleted());
        } catch (RuntimeException e) {
            fail(ra, e);
        }
        return "redirect:/admin/purchases/suppliers";
    }
}
