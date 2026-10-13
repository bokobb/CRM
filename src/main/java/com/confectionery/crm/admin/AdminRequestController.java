package com.confectionery.crm.admin;

import com.confectionery.crm.domain.*;
import com.confectionery.crm.service.*;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

import static com.confectionery.crm.admin.AdminSupport.*;

/**
 * Заявки на сырьё — заявки цеха на производство (ProductionRequest): что выпускаем и сколько замесов,
 * сырьё по рецептуре. Путь: «Новая» → склад сырья собрал и выдал («Собрано») → склад ГП оприходовал
 * продукцию («Оприходовано»). Те же действия и права, что в приложении (ProductionRequestService).
 */
@Controller
@RequestMapping("/admin/requests")
@RequiredArgsConstructor
public class AdminRequestController {

    private final ProductionRequestService requestService;
    private final IngredientService ingredientService;
    private final ProductionReportPdfService reportPdfService;
    private final CurrentUserService currentUserService;

    public record Chip(String key, String label, int count, boolean on, String href) {
    }

    @GetMapping
    @Transactional(readOnly = true)
    public String list(@RequestParam(defaultValue = "all") String status, @RequestParam(required = false) String from,
                       @RequestParam(required = false) String to, Model model) {
        LocalDate f = date(from, LocalDate.now().minusDays(7)), t = date(to, LocalDate.now().plusDays(1));
        List<ProductionRequest> base = requestService.findAll().stream()
                .filter(r -> !r.getDocumentDate().isBefore(f) && !r.getDocumentDate().isAfter(t)
                        || r.getStatus() == ProductionRequestStatus.NEW || r.getStatus() == ProductionRequestStatus.COLLECTED)
                .toList();
        Map<String, Integer> counts = new HashMap<>();
        base.forEach(r -> counts.merge(r.getStatus().name(), 1, Integer::sum));
        String qs = "&from=" + f + "&to=" + t;
        List<Chip> chips = new ArrayList<>();
        chips.add(new Chip("all", "Все", base.size(), status.equals("all"), "/admin/requests?status=all" + qs));
        for (ProductionRequestStatus s : ProductionRequestStatus.values()) {
            chips.add(new Chip(s.name(), s.getLabel(), counts.getOrDefault(s.name(), 0), status.equals(s.name()), "/admin/requests?status=" + s.name() + qs));
        }
        model.addAttribute("rows", base.stream().filter(r -> status.equals("all") || r.getStatus().name().equals(status)).toList());
        model.addAttribute("chips", chips);
        model.addAttribute("status", status);
        model.addAttribute("from", f);
        model.addAttribute("to", t);
        model.addAttribute("canCreate", currentUserService.hasRole(Role.ADMIN, Role.ACCOUNTANT, Role.TECHNOLOGIST));
        page(model, "Заявки на сырьё", "что выпускаем, сколько замесов и какое сырьё выдать", "requests");
        return "admin/requests/list";
    }

    @GetMapping("/{id:\\d+}")
    @Transactional(readOnly = true)
    public String view(@PathVariable Long id, Model model, RedirectAttributes ra) {
        ProductionRequest r;
        try {
            r = requestService.findById(id);
        } catch (IllegalArgumentException e) {
            fail(ra, e);
            return "redirect:/admin/requests";
        }
        Role role = currentUserService.role().orElse(null);
        model.addAttribute("r", r);
        model.addAttribute("mass", requestService.massSummary(r));
        model.addAttribute("canCollect", role == Role.ADMIN || role == Role.WAREHOUSE_RAW);
        model.addAttribute("canReceive", role == Role.ADMIN || role == Role.WAREHOUSE_FG);
        model.addAttribute("canComplete", role == Role.ADMIN || role == Role.ACCOUNTANT || role == Role.WAREHOUSE_RAW);
        model.addAttribute("isAdmin", role == Role.ADMIN);
        model.addAttribute("canSync", role == Role.ADMIN || role == Role.ACCOUNTANT);
        model.addAttribute("ingredients", ingredientService.findAllActive().stream()
                .sorted(Comparator.comparing(Ingredient::getName, String.CASE_INSENSITIVE_ORDER)).toList());
        page(model, "Заявки на сырьё", "", "requests");
        return "admin/requests/view";
    }

    public record FormLine(Long productId, String batches) {
    }

    @GetMapping("/new")
    @Transactional(readOnly = true)
    public String newForm(Model model) {
        return form(LocalDate.now(), List.of(), List.of(), model);
    }

    private String form(LocalDate date, List<FormLine> lines, List<String> errors, Model model) {
        List<Recipe> recipes = new ArrayList<>(requestService.recipesWithProducts());
        recipes.sort(Comparator.comparing(rc -> rc.getProduct().getName(), String.CASE_INSENSITIVE_ORDER));
        model.addAttribute("recipes", recipes);
        model.addAttribute("date", date);
        model.addAttribute("lines", lines);
        model.addAttribute("errors", errors);
        page(model, "Заявки на сырьё", "", "requests");
        return "admin/requests/form";
    }

    @PostMapping
    public String create(HttpServletRequest req, Model model, RedirectAttributes ra) {
        LocalDate date = AdminSupport.date(req.getParameter("date"), LocalDate.now());
        String[] ps = arr(req, "product"), bs = arr(req, "batches");
        List<FormLine> lines = new ArrayList<>();
        List<ProductionRequestService.Line> out = new ArrayList<>();
        boolean bad = false;
        for (int i = 0; i < ps.length; i++) {
            Long pid = AdminSupport.id(ps[i]);
            BigDecimal b = num(at(bs, i));
            if (pid == null && b == null) continue;
            lines.add(new FormLine(pid, at(bs, i)));
            if (pid == null || b == null || b.signum() <= 0) {
                bad = true;
                continue;
            }
            out.add(new ProductionRequestService.Line(pid, b));
        }
        List<String> errors = new ArrayList<>();
        if (bad) errors.add("Проверьте строки: изделие и число замесов больше нуля.");
        else if (out.isEmpty()) errors.add("Добавьте хотя бы одно изделие.");
        if (errors.isEmpty()) {
            try {
                List<ProductionRequest> created = requestService.createGrouped(out, currentUserService.username().orElse("admin"), date);
                ok(ra, created.size() == 1 ? "Заявка № " + created.get(0).getId() + " создана"
                        : "Создано заявок: " + created.size() + " (по группам изделий)");
                return created.size() == 1 ? "redirect:/admin/requests/" + created.get(0).getId() : "redirect:/admin/requests";
            } catch (RuntimeException e) {
                errors.add(e.getMessage());
            }
        }
        return form(date, lines, errors, model);
    }

    private String by() {
        return currentUserService.username().orElse("admin");
    }

    private String act(Long id, RedirectAttributes ra, String okText, Runnable action) {
        try {
            action.run();
            ok(ra, okText);
        } catch (RuntimeException e) {
            fail(ra, e);
        }
        return "redirect:/admin/requests/" + id;
    }

    @PostMapping("/{id:\\d+}/collect")
    public String collect(@PathVariable Long id, HttpServletRequest req, RedirectAttributes ra) {
        Set<Long> ids = new HashSet<>();
        for (String s : arr(req, "item")) {
            Long v = AdminSupport.id(s);
            if (v != null) ids.add(v);
        }
        return act(id, ra, "Заявка № " + id + " собрана: сырьё выдано в цех", () -> requestService.collect(id, ids, by()));
    }

    @PostMapping("/{id:\\d+}/lines/{lineId}/receive")
    public String receive(@PathVariable Long id, @PathVariable Long lineId, @RequestParam String qty, RedirectAttributes ra) {
        BigDecimal q = num(qty);
        return act(id, ra, "Продукция оприходована на склад", () -> {
            if (q == null || q.signum() < 0) throw new IllegalArgumentException("Укажите количество");
            requestService.receiveLine(id, lineId, q, by());
        });
    }

    @PostMapping("/{id:\\d+}/lines/{lineId}/received")
    public String changeReceived(@PathVariable Long id, @PathVariable Long lineId, @RequestParam String qty, RedirectAttributes ra) {
        BigDecimal q = num(qty);
        return act(id, ra, "Оприходованное количество исправлено", () -> {
            if (q == null || q.signum() < 0) throw new IllegalArgumentException("Укажите количество");
            requestService.changeReceived(id, lineId, q, by());
        });
    }

    @PostMapping("/{id:\\d+}/items/add")
    public String addItem(@PathVariable Long id, @RequestParam Long ingredient, @RequestParam String qty, RedirectAttributes ra) {
        BigDecimal q = num(qty);
        return act(id, ra, "Сырьё добавлено в заявку", () -> {
            if (q == null || q.signum() <= 0) throw new IllegalArgumentException("Укажите количество");
            requestService.addItem(id, ingredient, q, by(), currentUserService.hasRole(Role.ADMIN));
        });
    }

    @PostMapping("/{id:\\d+}/items/{itemId}/remove")
    public String removeItem(@PathVariable Long id, @PathVariable Long itemId, RedirectAttributes ra) {
        return act(id, ra, "Сырьё убрано из заявки", () -> requestService.removeItem(id, itemId, by(), currentUserService.hasRole(Role.ADMIN)));
    }

    @PostMapping("/{id:\\d+}/items/{itemId}/substitute")
    public String substitute(@PathVariable Long id, @PathVariable Long itemId, @RequestParam Long ingredient,
                             @RequestParam(required = false) String qty, RedirectAttributes ra) {
        BigDecimal q = num(qty);
        return act(id, ra, "Сырьё заменено", () -> requestService.substituteItem(id, itemId, ingredient, q, by()));
    }

    @PostMapping("/{id:\\d+}/cancel")
    public String cancel(@PathVariable Long id, RedirectAttributes ra) {
        return act(id, ra, "Заявка № " + id + " отменена", () -> {
            if (!currentUserService.hasRole(Role.ADMIN)) throw new IllegalStateException("Отменить заявку может директор");
            requestService.cancel(id, by());
        });
    }

    @PostMapping("/{id:\\d+}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        try {
            if (!currentUserService.hasRole(Role.ADMIN)) throw new IllegalStateException("Удалить заявку может директор");
            requestService.delete(id);
            ok(ra, "Заявка № " + id + " удалена");
            return "redirect:/admin/requests";
        } catch (RuntimeException e) {
            fail(ra, e);
            return "redirect:/admin/requests/" + id;
        }
    }

    @PostMapping("/{id:\\d+}/sync-1c")
    public String resync(@PathVariable Long id, RedirectAttributes ra) {
        return act(id, ra, "Заявка № " + id + " отправлена в 1С ещё раз", () -> requestService.resyncOneC(id));
    }

    @PostMapping("/{id:\\d+}/complete")
    public String complete(@PathVariable Long id, RedirectAttributes ra) {
        return act(id, ra, "Заявка № " + id + " завершена", () -> requestService.completeRequisition(id));
    }

    @PostMapping("/{id:\\d+}/reopen")
    public String reopen(@PathVariable Long id, RedirectAttributes ra) {
        return act(id, ra, "Завершение заявки № " + id + " отменено", () -> requestService.cancelCompletion(id));
    }

    /** Отчёт производства за день — PDF, как в приложении. */
    @GetMapping("/report.pdf")
    public ResponseEntity<byte[]> reportPdf(@RequestParam(required = false) String date) {
        LocalDate d = AdminSupport.date(date, LocalDate.now());
        byte[] pdf = reportPdfService.generate(d, d, requestService.dailyReport(d));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename("Производство_" + d + ".pdf", StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.APPLICATION_PDF).body(pdf);
    }
}
