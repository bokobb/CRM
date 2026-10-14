package com.confectionery.crm.admin;

import com.confectionery.crm.domain.*;
import com.confectionery.crm.repository.UserRepository;
import com.confectionery.crm.service.CurrentUserService;
import com.confectionery.crm.service.IngredientService;
import com.confectionery.crm.service.ProductionReportPdfService;
import com.confectionery.crm.service.ProductionRequestService;
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
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;

import static com.confectionery.crm.admin.AdminSupport.*;

/**
 * Заявки на сырьё по макету (часть 2) — заготовка для производства (ProductionRequest): что выпускаем и
 * сколько замесов, сырьё по рецептуре. Путь: «Новая» → склад сырья собрал по чек-листу и выдал
 * («Собрано») → склад ГП оприходовал выпуск («Оприходовано») → «Завершить» проводит
 * требование-накладную в 1С. Те же действия и права, что в приложении (ProductionRequestService,
 * ProductionRequestApiController).
 */
@Controller
@RequestMapping("/admin/requests")
@RequiredArgsConstructor
public class AdminRequestController {

    private final ProductionRequestService requestService;
    private final IngredientService ingredientService;
    private final ProductionReportPdfService reportPdfService;
    private final CurrentUserService currentUserService;
    private final UserRepository userRepository;

    static final AdminFormat F = new AdminFormat();

    /** Логин → имя (как в «Сотрудниках»), иначе как записано. */
    Function<String, String> who() {
        Map<String, String> cache = new HashMap<>();
        return u -> {
            if (u == null || u.isBlank()) return "—";
            return cache.computeIfAbsent(u, k -> userRepository.findByUsername(k)
                    .map(x -> x.getFullName() != null && !x.getFullName().isBlank() ? x.getFullName() : x.getUsername()).orElse(k));
        };
    }

    private boolean can(Role... roles) {
        return currentUserService.hasRole(roles);
    }

    private void need(String what, Role... roles) {
        if (!can(roles)) throw new IllegalStateException(what + " — нет прав у вашей роли");
    }

    private String by() {
        return currentUserService.username().orElse("admin");
    }

    private boolean admin() {
        return can(Role.ADMIN);
    }

    // ================================================================== список

    public record Chip(String key, String label, int count, boolean on, String href, boolean alert) {
    }

    public record Opt(String value, String label, boolean on) {
    }

    public record Tile(String href, String cls, String icon, String label, int value, String sub) {
    }

    static final List<String[]> PERIODS = List.of(new String[]{"today", "Дата: сегодня"}, new String[]{"yest", "Дата: вчера"},
            new String[]{"7", "Дата: 7 дней"}, new String[]{"14", "Дата: 14 дней"}, new String[]{"all", "Дата: все"});
    static final List<String[]> STATES = List.of(new String[]{"all", "Все"}, new String[]{"new", "Новые"}, new String[]{"collected", "Собрано"},
            new String[]{"received", "Оприходовано"}, new String[]{"done", "Завершены"}, new String[]{"off", "Отменены"});

    @GetMapping
    @Transactional(readOnly = true)
    public String list(@RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "7") String per,
                       @RequestParam(defaultValue = "all") String st, Model model) {
        List<ProductionRequest> all = requestService.findAll();
        LocalDate today = LocalDate.now();
        LocalDate from = switch (per) {
            case "today" -> today;
            case "yest" -> today.minusDays(1);
            case "14" -> today.minusDays(13);
            case "all" -> LocalDate.MIN;
            default -> today.minusDays(6);
        };
        LocalDate to = per.equals("yest") ? today.minusDays(1) : LocalDate.MAX;
        String[] words = AdminRequests.norm(q).isEmpty() ? new String[0] : AdminRequests.norm(q).split("\\s+");
        List<ProductionRequest> base = all.stream()
                .filter(r -> AdminRequests.matches(r, words))
                .filter(r -> AdminRequests.active(r) || (!r.getDocumentDate().isBefore(from) && !r.getDocumentDate().isAfter(to)))
                .toList();
        Map<String, Integer> cnt = new HashMap<>();
        base.forEach(r -> cnt.merge(AdminRequests.key(r), 1, Integer::sum));
        String qs = "&per=" + per + (q.isBlank() ? "" : "&q=" + java.net.URLEncoder.encode(q, StandardCharsets.UTF_8));
        List<Chip> chips = new ArrayList<>();
        for (String[] s : STATES) {
            int n = s[0].equals("all") ? base.size() : cnt.getOrDefault(s[0], 0);
            chips.add(new Chip(s[0], s[1], n, st.equals(s[0]), "/admin/requests?st=" + s[0] + qs, s[0].equals("new") && n > 0));
        }
        List<ProductionRequest> rows = new ArrayList<>(base.stream().filter(r -> st.equals("all") || AdminRequests.key(r).equals(st)).toList());
        rows.sort(Comparator.<ProductionRequest>comparingInt(r -> AdminRequests.active(r) ? 0 : 1)
                .thenComparingInt(r -> AdminRequests.active(r) ? AdminRequests.ORDER.indexOf(AdminRequests.key(r)) : 0)
                .thenComparing(ProductionRequest::getId, Comparator.reverseOrder()));
        model.addAttribute("tiles", tiles(all));
        model.addAttribute("rows", rows.stream().map(AdminRequests::row).toList());
        model.addAttribute("chips", chips);
        model.addAttribute("periods", PERIODS.stream().map(p -> new Opt(p[0], p[1], p[0].equals(per))).toList());
        model.addAttribute("q", q);
        model.addAttribute("per", per);
        model.addAttribute("st", st);
        model.addAttribute("canCreate", can(Role.ADMIN, Role.ACCOUNTANT, Role.TECHNOLOGIST));
        model.addAttribute("mergeJson", AdminJson.of(AdminRequests.mergeData(all)));
        page(model, "Заявки на сырьё", "заготовка для производства", "requests");
        return "admin/requests/list";
    }

    static String nos(List<ProductionRequest> list) {
        return String.join(", ", list.stream().map(r -> "№ " + r.getId() + " «" + r.getTitle() + "»").toList());
    }

    static List<Tile> tiles(List<ProductionRequest> all) {
        List<ProductionRequest> nw = all.stream().filter(r -> AdminRequests.key(r).equals("new")).sorted(Comparator.comparing(ProductionRequest::getId)).toList();
        List<ProductionRequest> col = all.stream().filter(r -> AdminRequests.key(r).equals("collected")).sorted(Comparator.comparing(ProductionRequest::getId)).toList();
        List<ProductionRequest> rec = all.stream().filter(r -> AdminRequests.key(r).equals("received")).sorted(Comparator.comparing(ProductionRequest::getId)).toList();
        Map<Long, AdminRequests.Need> need = AdminRequests.needNew(all);
        List<String> shortTxt = new ArrayList<>();
        need.values().forEach(n -> {
            if (AdminRequests.untracked(n.ingredient())) return;
            BigDecimal has = AdminRequests.nz(n.ingredient().getQuantityInStock());
            if (n.q().compareTo(has) > 0) shortTxt.add(n.ingredient().getName() + " " + AdminRequests.qu(n.q().subtract(has), AdminRequests.unit(n.ingredient())));
        });
        String s1 = nw.isEmpty() ? "новых заявок нет" : nos(nw) + (shortTxt.isEmpty() ? " · сырья хватает"
                : " · на " + (nw.size() > 1 ? "все" : "неё") + " не хватает: " + String.join(", ", shortTxt));
        String s2 = col.isEmpty() ? "выданного сырья без оприхода нет" : String.join(" · ", col.stream().map(r -> "№ " + r.getId() + " — оприходовано "
                + AdminRequests.got(r) + " из " + AdminRequests.main(r).size() + (AdminRequests.diff(r).isEmpty() ? "" : ", состав изменён после выдачи")).toList());
        String s3 = rec.isEmpty() ? "всё оприходованное завершено" : nos(rec) + " — требование-накладная в 1С не проведена";
        return List.of(
                new Tile("/admin/requests?st=new", !shortTxt.isEmpty() ? "kpi--alert" : !nw.isEmpty() ? "kpi--warm" : "", "materials", "Ждут сырья со склада", nw.size(), s1),
                new Tile("/admin/requests?st=collected", "", "stock", "Ждут оприхода", col.size(), s2),
                new Tile("/admin/requests?st=received", "", "s-done", "Ждут «Завершить»", rec.size(), s3));
    }

    // ================================================================== карточка

    @GetMapping("/{id:\\d+}")
    @Transactional(readOnly = true)
    public String view(@PathVariable Long id, Model model) {
        ProductionRequest r;
        try {
            r = requestService.findById(id);
        } catch (IllegalArgumentException e) {
            model.addAttribute("missing", id);
            page(model, "Заявки на сырьё", "", "requests");
            return "admin/requests/view";
        }
        Function<String, String> who = who();
        Map<Long, Recipe> recipes = recipes();
        Function<Long, Recipe> recipeOf = recipes::get;
        String k = AdminRequests.key(r);
        boolean got = AdminRequests.got(r) > 0;
        model.addAttribute("r", r);
        model.addAttribute("k", k);
        model.addAttribute("badge", AdminRequests.badge(k));
        model.addAttribute("batches", AdminRequests.batches(r));
        model.addAttribute("zamText", AdminRequests.zam(AdminRequests.batches(r)));
        model.addAttribute("shortText", String.join("; ", AdminRequests.stockShort(r).stream().map(x -> x.ingredient().getName() + " — нужно "
                + AdminRequests.qu(x.need(), AdminRequests.unit(x.ingredient())) + ", есть " + AdminRequests.qu(x.has(), AdminRequests.unit(x.ingredient()))).toList()));
        model.addAttribute("plan", AdminRequests.plan(r));
        model.addAttribute("fact", AdminRequests.fact(r));
        model.addAttribute("gotN", AdminRequests.got(r));
        model.addAttribute("mainN", AdminRequests.main(r).size());
        model.addAttribute("createdBy", who.apply(r.getCreatedBy()));
        model.addAttribute("steps", AdminRequests.steps(r, who));
        model.addAttribute("outs", AdminRequests.outs(r, recipeOf, who));
        model.addAttribute("raw", AdminRequests.raw(r, recipeOf));
        model.addAttribute("diff", AdminRequests.diff(r));
        model.addAttribute("diffText", AdminRequests.diffText(r));
        model.addAttribute("stockShort", AdminRequests.stockShort(r));
        model.addAttribute("hist", AdminRequests.history(r, who));
        model.addAttribute("off", k.equals("off") ? AdminRequests.offRevision(r) : null);
        model.addAttribute("offNote", AdminRequests.offNote(r));
        model.addAttribute("offBy", k.equals("off") && AdminRequests.offRevision(r) != null ? who.apply(AdminRequests.offRevision(r).getCreatedBy()) : null);
        model.addAttribute("collectedBy", who.apply(r.getCollectedBy()));
        model.addAttribute("mass", k.equals("off") ? null : requestService.massSummary(r));
        // кнопки — по статусу и роли (как в приложении)
        boolean raw = can(Role.ADMIN, Role.WAREHOUSE_RAW);
        model.addAttribute("canEdit", (k.equals("new") || k.equals("collected")) && !got && can(Role.ADMIN, Role.ACCOUNTANT, Role.TECHNOLOGIST));
        model.addAttribute("canComplete", k.equals("received") && can(Role.ADMIN, Role.ACCOUNTANT, Role.WAREHOUSE_RAW));
        model.addAttribute("canReopen", k.equals("done") && can(Role.ADMIN, Role.ACCOUNTANT, Role.WAREHOUSE_RAW));
        model.addAttribute("canCancel", !k.equals("off") && !k.equals("done") && admin());
        model.addAttribute("canDelete", k.equals("off") && admin());
        model.addAttribute("canRepeat", k.equals("off") && can(Role.ADMIN, Role.ACCOUNTANT, Role.TECHNOLOGIST));
        model.addAttribute("canItems", raw && !k.equals("off") && !k.equals("done"));
        model.addAttribute("canCollect", k.equals("new") && raw);
        model.addAttribute("canSettle", raw);
        model.addAttribute("canReceive", k.equals("collected") && can(Role.ADMIN, Role.WAREHOUSE_FG));
        model.addAttribute("canExtra", (k.equals("collected") || k.equals("received")) && can(Role.ADMIN, Role.WAREHOUSE_FG, Role.WAREHOUSE_RAW));
        model.addAttribute("canFix", !k.equals("off") && admin());
        model.addAttribute("canSync", !k.equals("off") && can(Role.ADMIN, Role.ACCOUNTANT));
        model.addAttribute("canCost", !k.equals("off") && can(Role.ADMIN, Role.ACCOUNTANT, Role.TECHNOLOGIST, Role.WAREHOUSE_RAW));
        model.addAttribute("pageJson", AdminJson.of(AdminRequests.cardData(r, ingredientService.findAllActive(), recipes.values())));
        page(model, "Заявки на сырьё", "", "requests");
        return "admin/requests/view";
    }

    private Map<Long, Recipe> recipes() {
        Map<Long, Recipe> m = new LinkedHashMap<>();
        for (Recipe rc : requestService.recipesWithProducts()) m.put(rc.getProduct().getId(), rc);
        return m;
    }

    // ================================================================== новая / изменить

    @GetMapping("/new")
    @Transactional(readOnly = true)
    public String newForm(@RequestParam(required = false) Long from, Model model, RedirectAttributes ra) {
        if (!can(Role.ADMIN, Role.ACCOUNTANT, Role.TECHNOLOGIST)) {
            fail(ra, new IllegalStateException("Создавать заявки могут бухгалтер, технолог и администратор"));
            return "redirect:/admin/requests";
        }
        ProductionRequest src = null;
        if (from != null) {
            try {
                src = requestService.findById(from);
            } catch (IllegalArgumentException e) {
                src = null;
            }
        }
        return form(null, src, model);
    }

    @GetMapping("/{id:\\d+}/edit")
    @Transactional(readOnly = true)
    public String editForm(@PathVariable Long id, Model model, RedirectAttributes ra) {
        ProductionRequest r;
        try {
            r = requestService.findById(id);
        } catch (IllegalArgumentException e) {
            fail(ra, e);
            return "redirect:/admin/requests";
        }
        String k = AdminRequests.key(r);
        if (!((k.equals("new") || k.equals("collected")) && AdminRequests.got(r) == 0) || !can(Role.ADMIN, Role.ACCOUNTANT, Role.TECHNOLOGIST)) {
            fail(ra, new IllegalStateException("Заявку № " + id + " уже не изменить: менять замесы можно, пока выпуск не оприходовали"));
            return "redirect:/admin/requests/" + id;
        }
        return form(r, null, model);
    }

    private String form(ProductionRequest r, ProductionRequest repeat, Model model) {
        return form(r, repeat, null, null, List.of(), model);
    }

    private String form(ProductionRequest r, ProductionRequest repeat, List<Map<String, Object>> posted, LocalDate postedDate, List<String> errors, Model model) {
        List<ProductionRequest> all = requestService.findAll();
        Map<String, Object> data = AdminRequests.formData(r, repeat, recipes().values(), ingredientService.findAllActive(), all,
                r == null ? List.of() : requestService.adjustments(r.getId()));
        if (posted != null) data.put("rows", posted);
        if (postedDate != null) data.put("doc", postedDate.toString());
        data.put("errors", errors);
        model.addAttribute("r", r);
        model.addAttribute("badge", r == null ? null : AdminRequests.badge(r));
        model.addAttribute("formJson", AdminJson.of(data));
        page(model, "Заявки на сырьё", "", "requests");
        return "admin/requests/form";
    }

    /** Строки формы как пришли — чтобы при ошибке показать их снова. */
    private static List<Map<String, Object>> posted(HttpServletRequest req) {
        String[] ps = arr(req, "product"), bs = arr(req, "batches");
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < ps.length; i++) {
            Long pid = AdminSupport.id(ps[i]);
            BigDecimal b = num(at(bs, i));
            if (pid == null && b == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("p", pid);
            m.put("b", b == null ? 0 : b.doubleValue());
            out.add(m);
        }
        return out;
    }

    private List<ProductionRequestService.Line> lines(HttpServletRequest req) {
        String[] ps = arr(req, "product"), bs = arr(req, "batches");
        List<ProductionRequestService.Line> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < ps.length; i++) {
            Long pid = AdminSupport.id(ps[i]);
            BigDecimal b = num(at(bs, i));
            if (pid == null && b == null) continue;
            if (pid == null || b == null || b.signum() <= 0) throw new IllegalArgumentException("Проверьте строки: изделие и число замесов больше нуля");
            if (!seen.add(pid)) throw new IllegalArgumentException("Изделие дважды — сложите в одну строку");
            out.add(new ProductionRequestService.Line(pid, b.setScale(3, RoundingMode.HALF_UP).stripTrailingZeros()));
        }
        if (out.isEmpty()) throw new IllegalArgumentException("Добавьте хотя бы одно изделие");
        return out;
    }

    @PostMapping
    public String create(HttpServletRequest req, Model model, RedirectAttributes ra) {
        LocalDate date = AdminSupport.date(req.getParameter("date"), LocalDate.now());
        try {
            need("Создать заявку", Role.ADMIN, Role.ACCOUNTANT, Role.TECHNOLOGIST);
            List<ProductionRequest> made = requestService.createGrouped(lines(req), by(), date);
            if (made.size() == 1) {
                ok(ra, "Создана заявка № " + made.get(0).getId() + " «" + made.get(0).getTitle() + "» — склад сырья её уже видит");
                return "redirect:/admin/requests/" + made.get(0).getId();
            }
            ok(ra, "Создано " + F.count(made.size(), "заявка", "заявки", "заявок") + ": " + nos(made));
            return "redirect:/admin/requests?st=new";
        } catch (RuntimeException e) {
            return form(null, null, posted(req), date, List.of(e.getMessage() == null ? "Не получилось — попробуйте ещё раз" : e.getMessage()), model);
        }
    }

    @PostMapping("/{id:\\d+}/edit")
    public String update(@PathVariable Long id, HttpServletRequest req, Model model, RedirectAttributes ra) {
        LocalDate date = AdminSupport.date(req.getParameter("date"), null);
        ProductionRequest before;
        try {
            before = requestService.findById(id);
        } catch (IllegalArgumentException e) {
            fail(ra, e);
            return "redirect:/admin/requests";
        }
        try {
            need("Изменить заявку", Role.ADMIN, Role.ACCOUNTANT, Role.TECHNOLOGIST);
            boolean keep = "true".equals(req.getParameter("keep"));
            boolean issued = before.getStatus() == ProductionRequestStatus.COLLECTED;
            requestService.update(id, lines(req), by(), date, false, keep);
            ok(ra, "Заявка № " + id + " сохранена" + (issued ? " — склад сырья сдвинут на разницу" : ""));
            return "redirect:/admin/requests/" + id;
        } catch (RuntimeException e) {
            return form(before, null, posted(req), date, List.of(e.getMessage() == null ? "Не получилось — попробуйте ещё раз" : e.getMessage()), model);
        }
    }

    // ================================================================== действия

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
        return act(id, ra, "Заявка № " + id + " собрана: сырьё выдано в цех и списано со склада. Теперь ждёт оприхода", () -> {
            need("Собрать заявку", Role.ADMIN, Role.WAREHOUSE_RAW);
            requestService.collect(id, ids, by());
        });
    }

    @PostMapping("/{id:\\d+}/lines/{lineId}/receive")
    public String receive(@PathVariable Long id, @PathVariable Long lineId, @RequestParam String qty, RedirectAttributes ra) {
        BigDecimal q = num(qty);
        try {
            need("Оприходовать", Role.ADMIN, Role.WAREHOUSE_FG);
            if (q == null || q.signum() <= 0) throw new IllegalArgumentException("Введите, сколько штук пришло на склад");
            ProductionRequest r = requestService.receiveLine(id, lineId, q, by());
            String msg = "Продукция оприходована на склад";
            for (ProductionRequestLine l : r.getLines()) {
                if (l.getId().equals(lineId)) msg = "Оприходовано: " + AdminRequests.shortName(l.getProduct().getName()) + " — " + F.num(q) + AdminFormat.NB + "шт";
            }
            if (r.getStatus() == ProductionRequestStatus.RECEIVED) msg += ". Заявка оприходована целиком — осталось «Завершить»";
            ok(ra, msg);
        } catch (RuntimeException e) {
            fail(ra, e);
        }
        return "redirect:/admin/requests/" + id;
    }

    @PostMapping("/{id:\\d+}/lines/{lineId}/received")
    public String changeReceived(@PathVariable Long id, @PathVariable Long lineId, @RequestParam String qty, RedirectAttributes ra) {
        BigDecimal q = num(qty);
        return act(id, ra, "Оприходованное исправлено", () -> {
            need("Исправить оприходованное", Role.ADMIN);
            if (q == null || q.signum() < 0) throw new IllegalArgumentException("Введите количество");
            requestService.changeReceived(id, lineId, q, by());
        });
    }

    @PostMapping("/{id:\\d+}/extra")
    public String extra(@PathVariable Long id, @RequestParam Long product, @RequestParam String qty, RedirectAttributes ra) {
        BigDecimal q = num(qty);
        return act(id, ra, "Оприходовано сверх заявки", () -> {
            need("Оприходовать сверх заявки", Role.ADMIN, Role.WAREHOUSE_FG, Role.WAREHOUSE_RAW);
            if (q == null || q.signum() <= 0) throw new IllegalArgumentException("Введите количество больше нуля");
            requestService.receiveExtra(id, product, q, by(), admin());
        });
    }

    @PostMapping("/{id:\\d+}/items/add")
    public String addItem(@PathVariable Long id, @RequestParam Long ingredient, @RequestParam String qty, RedirectAttributes ra) {
        BigDecimal q = num(qty);
        return act(id, ra, "Сырьё добавлено в заявку", () -> {
            need("Добавить сырьё", Role.ADMIN, Role.WAREHOUSE_RAW);
            if (q == null || q.signum() <= 0) throw new IllegalArgumentException("Введите количество больше нуля");
            requestService.addItem(id, ingredient, q, by(), admin());
        });
    }

    @PostMapping("/{id:\\d+}/items/{itemId}/remove")
    public String removeItem(@PathVariable Long id, @PathVariable Long itemId, RedirectAttributes ra) {
        return act(id, ra, "Сырьё убрано из заявки", () -> {
            need("Убрать сырьё", Role.ADMIN, Role.WAREHOUSE_RAW);
            requestService.removeItem(id, itemId, by(), admin());
        });
    }

    /** Без {@code ingredient} — только количество; с ним — замена сырья. */
    @PostMapping("/{id:\\d+}/items/{itemId}/substitute")
    public String substitute(@PathVariable Long id, @PathVariable Long itemId, @RequestParam(required = false) Long ingredient,
                             @RequestParam(required = false) String qty, RedirectAttributes ra) {
        BigDecimal q = num(qty);
        return act(id, ra, ingredient == null ? "Количество изменено" : "Сырьё заменено", () -> {
            need("Изменить состав", Role.ADMIN, Role.WAREHOUSE_RAW);
            requestService.substituteItem(id, itemId, ingredient, q, by(), admin());
        });
    }

    @PostMapping("/{id:\\d+}/settle")
    public String settle(@PathVariable Long id, RedirectAttributes ra) {
        return act(id, ra, "Рассчитались по заявке № " + id, () -> {
            need("«Рассчитались»", Role.ADMIN, Role.WAREHOUSE_RAW);
            requestService.settleComposition(id, by());
        });
    }

    @PostMapping("/{id:\\d+}/cancel")
    public String cancel(@PathVariable Long id, @RequestParam(required = false) String reason, RedirectAttributes ra) {
        return act(id, ra, "Заявка № " + id + " отменена", () -> {
            need("Отменить заявку", Role.ADMIN);
            requestService.cancel(id, by(), reason);
        });
    }

    @PostMapping("/{id:\\d+}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        try {
            need("Удалить заявку", Role.ADMIN);
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
        return act(id, ra, "Заявка № " + id + " отправлена в 1С ещё раз", () -> {
            need("Отправить в 1С", Role.ADMIN, Role.ACCOUNTANT);
            requestService.resyncOneC(id);
        });
    }

    @PostMapping("/{id:\\d+}/complete")
    public String complete(@PathVariable Long id, RedirectAttributes ra) {
        try {
            need("Завершить", Role.ADMIN, Role.ACCOUNTANT, Role.WAREHOUSE_RAW);
            requestService.completeRequisition(id, by());
            ok(ra, "Заявка № " + id + " завершена — требование-накладная проведена в 1С");
            ra.addFlashAttribute("toastUndo", "/admin/requests/" + id + "/reopen");
        } catch (RuntimeException e) {
            fail(ra, e);
        }
        return "redirect:/admin/requests/" + id;
    }

    @PostMapping("/{id:\\d+}/reopen")
    public String reopen(@PathVariable Long id, RedirectAttributes ra) {
        return act(id, ra, "Завершение отменено — состав снова можно править", () -> {
            need("Отменить завершение", Role.ADMIN, Role.ACCOUNTANT, Role.WAREHOUSE_RAW);
            requestService.cancelCompletion(id, by());
        });
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
