package com.confectionery.crm.admin;

import com.confectionery.crm.api.dto.ProductionCostDto;
import com.confectionery.crm.api.dto.TechCardDto;
import com.confectionery.crm.api.dto.TechCardRequest;
import com.confectionery.crm.api.dto.TechCardSummaryDto;
import com.confectionery.crm.domain.*;
import com.confectionery.crm.repository.IngredientRepository;
import com.confectionery.crm.repository.ProductRepository;
import com.confectionery.crm.repository.ProductionRequestRepository;
import com.confectionery.crm.repository.PurchaseRepository;
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
 * Производство по макету (часть 3): себестоимость за день — плитки, 14 дней на графике, заявки дня с бригадой и
 * себестоимостью изделий (ProductionCostService — как в приложении), окно «Бригада» (ProductionBrigadeService:
 * один человек — одна бригада за день), отчёт за день (что оприходовали, PDF — /admin/requests/report.pdf);
 * себестоимость заявки — за единицу, рецептура «норма и выдано» с покупкой, по которой взята цена, бригада,
 * накладные цеха, оприходовано. Зарплату и накладные видят администратор и бухгалтер, технолог — только сырьё.
 * Техкарта: этапы, температура и время, ККТ, органолептика, упаковка, срок годности (TechCardService).
 */
@Controller
@RequiredArgsConstructor
public class AdminProductionController {

    private final ProductionCostService costService;
    private final ProductionBrigadeService brigadeService;
    private final EmployeeService employeeService;
    private final TechCardService techCardService;
    private final CurrentUserService currentUserService;
    private final ProductRepository productRepository;
    private final IngredientRepository ingredientRepository;
    private final ProductionRequestRepository requestRepository;
    private final ProductionRequestService requestService;
    private final SalaryFundService salaryFundService;
    private final PurchaseRepository purchaseRepository;

    private boolean labor() {
        return currentUserService.hasRole(Role.ADMIN, Role.ACCOUNTANT);
    }

    /** Бригаду назначают те же, кто в приложении (ProductionCostApiController). */
    private boolean canBrigade() {
        return currentUserService.hasRole(Role.ADMIN, Role.TECHNOLOGIST, Role.ACCOUNTANT);
    }

    private Map<Long, Product> products(Collection<Long> ids) {
        Map<Long, Product> out = new HashMap<>();
        if (!ids.isEmpty()) productRepository.findAllById(ids).forEach(p -> out.put(p.getId(), p));
        return out;
    }

    @GetMapping("/admin/production")
    @Transactional(readOnly = true)
    public String day(@RequestParam(required = false) String date, Model model) {
        LocalDate today = LocalDate.now();
        LocalDate d = AdminSupport.date(date, today);
        if (d.isAfter(today)) d = today;
        if (d.isBefore(today.minusYears(1))) d = today.minusYears(1);
        boolean labor = labor();
        ProductionCostDto dto = costService.day(d, labor);
        List<ProductionRequest> window = requestRepository.findByDocumentDateBetween(d.minusDays(13), d);
        Map<Long, ProductionRequest> byId = new HashMap<>();
        Map<LocalDate, Long> counts = new HashMap<>();
        for (ProductionRequest r : window) {
            byId.put(r.getId(), r);
            if (r.getStatus() != ProductionRequestStatus.CANCELLED) counts.merge(r.getDocumentDate(), 1L, Long::sum);
        }
        final LocalDate day = d;
        List<ProductionRequest> dayRequests = window.stream().filter(r -> r.getDocumentDate().equals(day) && r.getStatus() != ProductionRequestStatus.CANCELLED)
                .sorted(Comparator.comparing(ProductionRequest::getId)).toList();
        Set<Long> pids = new HashSet<>();
        dto.requests().forEach(r -> r.lines().forEach(l -> pids.add(l.productId())));
        Map<Long, Product> prods = products(pids);
        List<AdminProduction.Card> cards = dto.requests().stream().map(r -> AdminProduction.card(r, byId.get(r.id()), prods, labor)).toList();
        BigDecimal fg14 = BigDecimal.ZERO, sale14 = BigDecimal.ZERO;
        for (ProductionCostDto.Day h : dto.history()) {
            fg14 = fg14.add(h.finishedCost());
            sale14 = sale14.add(h.finishedSaleValue());
        }
        model.addAttribute("d", dto);
        model.addAttribute("date", d);
        model.addAttribute("isToday", d.equals(today));
        model.addAttribute("sunday", AdminProduction.sunday(d));
        model.addAttribute("tiles", AdminProduction.tiles(dto, cards, labor));
        model.addAttribute("cards", cards);
        model.addAttribute("from", dto.history().isEmpty() ? d : dto.history().get(0).date());
        model.addAttribute("fg14", fg14);
        model.addAttribute("sale14", sale14);
        model.addAttribute("mk14", AdminProduction.markup(sale14, fg14));
        model.addAttribute("unpriced", dto.unpricedIngredients().stream().filter(n -> !AdminRequests.untracked(n)).toList());
        model.addAttribute("off", window.stream().filter(r -> r.getDocumentDate().equals(day) && r.getStatus() == ProductionRequestStatus.CANCELLED)
                .sorted(Comparator.comparing(ProductionRequest::getId)).toList());
        model.addAttribute("fund", dto.totals().overheadFund());
        model.addAttribute("mode", AdminProduction.overheadMode(dayRequests));
        model.addAttribute("labor", labor);
        model.addAttribute("canBrigade", canBrigade());
        model.addAttribute("canNew", currentUserService.hasRole(Role.ADMIN, Role.ACCOUNTANT, Role.TECHNOLOGIST));
        model.addAttribute("hist", AdminProduction.histRows(dto, counts, today));
        model.addAttribute("chartJson", AdminJson.of(AdminProduction.chartData(dto, counts, today)));
        model.addAttribute("brigJson", AdminJson.of(AdminProduction.brigadeData(d, employeeService.findAll(), dayRequests, labor)));
        model.addAttribute("repJson", AdminJson.of(AdminProduction.reportData(d, requestService.dailyReport(d))));
        page(model, "Производство", AdminProduction.F.dayTitle(d) + (d.equals(today) ? " · сегодня" : d.equals(today.minusDays(1)) ? " · вчера" : ""), "production");
        model.addAttribute("subPick", d.toString());
        model.addAttribute("subBase", "/admin/production");
        model.addAttribute("subParam", "date");
        model.addAttribute("subKeep", null);
        model.addAttribute("dayMin", today.minusYears(1).toString());
        model.addAttribute("dayMax", today.toString());
        return "admin/production/day";
    }

    @GetMapping("/admin/production/requests/{id}")
    @Transactional(readOnly = true)
    public String request(@PathVariable Long id, Model model) {
        ProductionRequest req = requestRepository.findById(id).orElse(null);
        if (req == null) {
            model.addAttribute("missing", id);
            page(model, "Производство", "", "production");
            return "admin/production/request";
        }
        boolean labor = labor();
        LocalDate d = req.getDocumentDate();
        model.addAttribute("req", req);
        model.addAttribute("key", AdminRequests.key(req));
        model.addAttribute("badge", AdminRequests.badge(req));
        model.addAttribute("date", d);
        page(model, "Производство", "", "production");
        if (req.getStatus() == ProductionRequestStatus.CANCELLED) return "admin/production/request";
        ProductionCostDto.RequestDetail r = costService.request(id, labor);
        ProductionCostDto.Request x = r.request();
        Map<Long, Product> prods = products(x.lines().stream().map(ProductionCostDto.Line::productId).toList());
        List<Long> ingIds = r.ingredients().stream().map(ProductionCostDto.Ingredient::ingredientId).toList();
        Map<Long, AdminProduction.PriceRef> refs = AdminProduction.priceRefs(ingIds.isEmpty() ? List.of() : purchaseRepository.findPricedItemsNewestFirst(ingIds), d);
        List<ProductionRequest> dayRequests = requestRepository.findByDocumentDateBetweenAndStatusNot(d, d, ProductionRequestStatus.CANCELLED)
                .stream().sorted(Comparator.comparing(ProductionRequest::getId)).toList();
        List<Employee> employees = employeeService.findAll();
        BigDecimal issued = AdminProduction.nz(x.issuedCost()), norm = AdminProduction.nz(x.normCost());
        BigDecimal lab = labor ? AdminProduction.nz(x.laborCost()) : BigDecimal.ZERO, over = labor ? AdminProduction.nz(x.overheadCost()) : BigDecimal.ZERO;
        BigDecimal cost = issued.add(lab).add(over), sale = BigDecimal.ZERO, units = BigDecimal.ZERO;
        boolean anyFg = false;
        int got = 0;
        for (ProductionCostDto.Line l : x.lines()) {
            units = units.add(AdminProduction.nz(l.quantity()));
            if (l.received()) got++;
            if (l.semiFinished()) continue;
            anyFg = true;
            if (l.salePrice() != null) sale = sale.add(l.salePrice().multiply(AdminProduction.nz(l.quantity())));
        }
        BigDecimal fgCost = x.lines().stream().filter(l -> !l.semiFinished()).map(l -> AdminProduction.nz(l.cost())).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal dev = issued.subtract(norm);
        BigDecimal devP = norm.signum() > 0 ? dev.multiply(BigDecimal.valueOf(100)).divide(norm, 4, java.math.RoundingMode.HALF_UP) : BigDecimal.ZERO;
        BigDecimal fund = labor ? salaryFundService.dailyFund(EmployeeCostGroup.PRODUCTION, d) : null;
        List<AdminProduction.RawRow> raw = AdminProduction.rawRows(r.ingredients(), refs);
        model.addAttribute("r", r);
        model.addAttribute("x", x);
        model.addAttribute("units", AdminProduction.unitRows(x, prods));
        model.addAttribute("raw", raw);
        model.addAttribute("rawNorm", raw.stream().map(y -> AdminProduction.nz(y.normCost())).reduce(BigDecimal.ZERO, BigDecimal::add));
        model.addAttribute("rawIssued", raw.stream().map(y -> AdminProduction.nz(y.i().issuedCost())).reduce(BigDecimal.ZERO, BigDecimal::add));
        model.addAttribute("cost", cost);
        model.addAttribute("sale", sale);
        model.addAttribute("anyFg", anyFg);
        model.addAttribute("mk", AdminProduction.markup(sale, fgCost));
        model.addAttribute("dev", dev);
        model.addAttribute("devP", devP);
        model.addAttribute("outQty", units);
        model.addAttribute("got", got);
        model.addAttribute("members", AdminProduction.members(req, labor));
        model.addAttribute("fund", fund);
        model.addAttribute("dayCount", dayRequests.size());
        model.addAttribute("share", fund != null && fund.signum() > 0 ? over.multiply(BigDecimal.valueOf(100)).divide(fund, 2, java.math.RoundingMode.HALF_UP) : BigDecimal.ZERO);
        String mode = AdminProduction.overheadMode(dayRequests);
        model.addAttribute("mode", mode);
        model.addAttribute("overHint", AdminProduction.overheadHint(employees, d, mode));
        model.addAttribute("unpriced", r.unpricedIngredients().stream().filter(n -> !AdminRequests.untracked(n)).toList());
        model.addAttribute("main", AdminRequests.main(req).size());
        model.addAttribute("batches", AdminRequests.batches(req));
        model.addAttribute("plan", AdminRequests.plan(req));
        model.addAttribute("outs", AdminProduction.got(req));
        model.addAttribute("zamText", AdminRequests.zam(AdminRequests.batches(req)));
        model.addAttribute("labor", labor);
        model.addAttribute("canBrigade", canBrigade());
        model.addAttribute("brigJson", AdminJson.of(AdminProduction.brigadeData(d, employees, dayRequests, labor)));
        return "admin/production/request";
    }

    @PostMapping("/admin/production/requests/{id}/brigade")
    public String brigade(@PathVariable Long id, HttpServletRequest req, RedirectAttributes ra) {
        String back = req.getParameter("back");
        String to = back != null && back.matches("/admin/production(/requests/\\d+)?(\\?date=\\d{4}-\\d\\d-\\d\\d)?") ? back : "/admin/production/requests/" + id;
        try {
            if (!canBrigade()) throw new IllegalStateException("Бригаду назначают технолог, бухгалтер и администратор");
            List<Long> ids = new ArrayList<>();
            for (String s : arr(req, "employee")) {
                Long v = AdminSupport.id(s);
                if (v != null && !ids.contains(v)) ids.add(v);
            }
            brigadeService.setBrigade(id, ids);
            ok(ra, "Бригада заявки № " + id + ": " + (ids.isEmpty() ? "снята" : ids.size() + AdminFormat.NB + "чел.") + " — себестоимость пересчитана");
        } catch (RuntimeException e) {
            fail(ra, e);
        }
        return "redirect:" + to;
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

    /**
     * Техкарта как в макете: паспорт, состав и выход, себестоимость (цены видят администратор и бухгалтер), расчёт на план
     * с остатками сырья, процесс с ККТ, качество, этикетка; справа — итог и замечания. ?edit=1 — правка полей техкарты.
     */
    @GetMapping("/admin/techcards/{productId}")
    @Transactional(readOnly = true)
    public String techcard(@PathVariable Long productId, @RequestParam(defaultValue = "0") int edit, Model model) {
        TechCardDto t = techCardService.get(productId);
        AdminTechCards.Calc c = calc(t);
        model.addAttribute("t", t);
        model.addAttribute("c", c);
        model.addAttribute("semi", productRepository.findById(productId).map(Product::isSemiFinished).orElse(false));
        model.addAttribute("planJson", AdminJson.of(AdminTechCards.planData(c, t.unit())));
        model.addAttribute("edit", edit == 1 && canEditCard());
        model.addAttribute("canEdit", canEditCard());
        model.addAttribute("labor", labor());
        page(model, "Техкарта", t.name(), "techcards");
        return "admin/techcards/view";
    }

    /** Карта на замес для бригады: сырьё на n замесов с отметками, выход, этапы с фактом по ККТ, подписи. A4. */
    @GetMapping("/admin/techcards/{productId}/sheet")
    @Transactional(readOnly = true)
    public String techcardSheet(@PathVariable Long productId, @RequestParam(defaultValue = "1") int n, Model model) {
        TechCardDto t = techCardService.get(productId);
        AdminTechCards.Calc c = calc(t);
        int batches = Math.max(1, Math.min(50, n));
        BigDecimal nb = BigDecimal.valueOf(batches);
        BigDecimal out = c.yield() == null ? null : c.yield().multiply(nb);
        model.addAttribute("t", t);
        model.addAttribute("c", c);
        model.addAttribute("n", batches);
        model.addAttribute("nb", nb);
        model.addAttribute("outQty", out);
        // выход в коробках: целые коробки и остаток в штуках
        boolean boxes = out != null && c.perBox() != null && c.perBox().signum() > 0;
        model.addAttribute("outBoxes", boxes ? out.divideToIntegralValue(c.perBox()).longValue() : null);
        model.addAttribute("outRest", boxes ? out.remainder(c.perBox()).longValue() : 0L);
        return "admin/techcards/sheet";
    }

    private AdminTechCards.Calc calc(TechCardDto t) {
        Map<Long, BigDecimal> stock = new HashMap<>();
        List<Long> ids = t.items().stream().map(TechCardDto.Item::ingredientId).filter(Objects::nonNull).toList();
        for (Ingredient i : ingredientRepository.findAllById(ids)) stock.put(i.getId(), i.getQuantityInStock());
        Product p = productRepository.findById(t.productId()).orElse(null);
        BigDecimal perBox = p == null ? null : AdminOrders.boxUnits(p);
        return AdminTechCards.calc(t, stock, perBox, p != null && p.isSemiFinished(), labor(), LocalDate.now());
    }

    private boolean canEditCard() {
        return currentUserService.hasRole(Role.ADMIN, Role.TECHNOLOGIST);
    }

    @PostMapping("/admin/techcards/{productId}")
    public String saveTechcard(@PathVariable Long productId, HttpServletRequest req, RedirectAttributes ra) {
        try {
            if (!canEditCard()) throw new IllegalStateException("Техкарту правит технолог или администратор");
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
