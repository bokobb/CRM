package com.confectionery.crm.admin;

import com.confectionery.crm.api.dto.ProductionCostDto;
import com.confectionery.crm.api.dto.TechCardDto;
import com.confectionery.crm.api.dto.TechCardRequest;
import com.confectionery.crm.api.dto.TechCardSummaryDto;
import com.confectionery.crm.domain.Employee;
import com.confectionery.crm.domain.Role;
import com.confectionery.crm.service.*;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.*;

import static com.confectionery.crm.admin.AdminSupport.*;

/**
 * Производство: заявки дня × техкарты — норма сырья, выдано, зарплата бригад, себестоимость и
 * выручка (ProductionCostService), бригады на заявку. Техкарта: этапы, температура и время, ККТ,
 * органолептика, упаковка, срок годности (TechCardService). Зарплату видят директор и бухгалтер.
 */
@Controller
@RequiredArgsConstructor
public class AdminProductionController {

    private final ProductionCostService costService;
    private final ProductionBrigadeService brigadeService;
    private final EmployeeService employeeService;
    private final TechCardService techCardService;
    private final CurrentUserService currentUserService;

    private boolean labor() {
        return currentUserService.hasRole(Role.ADMIN, Role.ACCOUNTANT);
    }

    @GetMapping("/admin/production")
    @Transactional(readOnly = true)
    public String day(@RequestParam(required = false) String date, Model model) {
        LocalDate d = AdminSupport.date(date, LocalDate.now());
        ProductionCostDto dto = costService.day(d, labor());
        model.addAttribute("d", dto);
        model.addAttribute("date", d);
        model.addAttribute("prev", d.minusDays(1));
        model.addAttribute("next", d.plusDays(1));
        model.addAttribute("labor", labor());
        page(model, "Производство", "себестоимость дня: заявки × техкарты", "production");
        return "admin/production/day";
    }

    @GetMapping("/admin/production/requests/{id}")
    @Transactional(readOnly = true)
    public String request(@PathVariable Long id, Model model) {
        ProductionCostDto.RequestDetail r = costService.request(id, labor());
        Set<Long> inBrigade = new HashSet<>();
        r.request().brigade().forEach(m -> inBrigade.add(m.employeeId()));
        model.addAttribute("r", r);
        model.addAttribute("labor", labor());
        model.addAttribute("employees", employeeService.findAll().stream().filter(Employee::isActive)
                .sorted(Comparator.comparing(Employee::getFullName, String.CASE_INSENSITIVE_ORDER)).toList());
        model.addAttribute("inBrigade", inBrigade);
        model.addAttribute("canBrigade", currentUserService.hasRole(Role.ADMIN, Role.TECHNOLOGIST, Role.ACCOUNTANT));
        page(model, "Производство", r.request().title(), "production");
        return "admin/production/request";
    }

    @PostMapping("/admin/production/requests/{id}/brigade")
    public String brigade(@PathVariable Long id, HttpServletRequest req, RedirectAttributes ra) {
        try {
            if (!currentUserService.hasRole(Role.ADMIN, Role.TECHNOLOGIST, Role.ACCOUNTANT)) throw new IllegalStateException("Бригаду назначает технолог");
            List<Long> ids = new ArrayList<>();
            for (String s : arr(req, "employee")) {
                Long v = AdminSupport.id(s);
                if (v != null) ids.add(v);
            }
            brigadeService.setBrigade(id, ids);
            ok(ra, "Бригада сохранена: " + ids.size() + " чел.");
        } catch (RuntimeException e) {
            fail(ra, e);
        }
        return "redirect:/admin/production/requests/" + id;
    }

    // ------------------------------------------------------------------ техкарты

    @GetMapping("/admin/techcards")
    @Transactional(readOnly = true)
    public String techcards(@RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "all") String group,
                            @RequestParam(defaultValue = "0") int draft, Model model) {
        String nq = AdminOrderController.norm(q);
        List<TechCardSummaryDto> all = techCardService.list().stream()
                .filter(t -> nq.isEmpty() || AdminOrderController.norm(t.name()).contains(nq))
                .filter(t -> draft == 0 || t.draft())
                .sorted(Comparator.comparing(TechCardSummaryDto::name, String.CASE_INSENSITIVE_ORDER)).toList();
        Map<String, Integer> counts = new TreeMap<>();
        all.forEach(t -> counts.merge(t.group() == null ? "Без группы" : t.group(), 1, Integer::sum));
        List<AdminRecipeController.Chip> chips = new ArrayList<>();
        String base = "&q=" + java.net.URLEncoder.encode(q, java.nio.charset.StandardCharsets.UTF_8) + (draft == 1 ? "&draft=1" : "");
        chips.add(new AdminRecipeController.Chip("all", "Все", all.size(), group.equals("all"), "/admin/techcards?group=all" + base));
        counts.forEach((k, v) -> chips.add(new AdminRecipeController.Chip(k, k, v, group.equals(k),
                "/admin/techcards?group=" + java.net.URLEncoder.encode(k, java.nio.charset.StandardCharsets.UTF_8) + base)));
        model.addAttribute("rows", all.stream().filter(t -> group.equals("all") || group.equals(t.group() == null ? "Без группы" : t.group())).toList());
        model.addAttribute("chips", chips);
        model.addAttribute("group", group);
        model.addAttribute("q", q);
        model.addAttribute("draft", draft == 1);
        page(model, "Техкарта", "этапы, ККТ, органолептика, хранение", "techcards");
        return "admin/techcards/list";
    }

    @GetMapping("/admin/techcards/{productId}")
    @Transactional(readOnly = true)
    public String techcard(@PathVariable Long productId, @RequestParam(defaultValue = "0") int edit, Model model) {
        TechCardDto t = techCardService.get(productId);
        model.addAttribute("t", t);
        model.addAttribute("edit", edit == 1 && canEditCard());
        model.addAttribute("canEdit", canEditCard());
        model.addAttribute("labor", labor());
        page(model, "Техкарта", t.name(), "techcards");
        return "admin/techcards/view";
    }

    private boolean canEditCard() {
        return currentUserService.hasRole(Role.ADMIN, Role.TECHNOLOGIST);
    }

    @PostMapping("/admin/techcards/{productId}")
    public String saveTechcard(@PathVariable Long productId, HttpServletRequest req, RedirectAttributes ra) {
        try {
            if (!canEditCard()) throw new IllegalStateException("Техкарту правит технолог или директор");
            String[] ti = arr(req, "stTitle"), de = arr(req, "stDesc"), te = arr(req, "stTemp"), du = arr(req, "stDur"), cc = arr(req, "stCcp");
            List<TechCardDto.Stage> stages = new ArrayList<>();
            for (int i = 0; i < ti.length; i++) {
                if (ti[i].isBlank() && at(de, i).isBlank()) continue;
                stages.add(new TechCardDto.Stage(ti[i].trim(), at(de, i).trim(), at(te, i).trim(), at(du, i).trim(), "1".equals(at(cc, i))));
            }
            techCardService.save(productId, new TechCardRequest(p(req, "shelfLife"), p(req, "storageConditions"), p(req, "packaging"),
                    p(req, "qualityLook"), p(req, "qualityColor"), p(req, "qualityCrumb"), p(req, "qualityTaste"), stages));
            ok(ra, "Техкарта сохранена");
        } catch (RuntimeException e) {
            fail(ra, e);
            return "redirect:/admin/techcards/" + productId + "?edit=1";
        }
        return "redirect:/admin/techcards/" + productId;
    }

    private static String p(HttpServletRequest req, String name) {
        String v = req.getParameter(name);
        return v == null || v.isBlank() ? null : v.trim();
    }
}
