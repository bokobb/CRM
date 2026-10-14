package com.confectionery.crm.admin;

import com.confectionery.crm.domain.*;
import com.confectionery.crm.repository.*;
import com.confectionery.crm.service.*;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;

import static com.confectionery.crm.admin.AdminSupport.*;

/**
 * Склад сырья по макету (часть 2): остатки с состоянием и «хватит на», нужно по новым заявкам, цена последней
 * покупки; журнал движений (выдача в цех по заявкам, возвраты, приход по покупкам и документам, списания);
 * карточка сырья; документ «Поступление»/«Списание» (StockService — уходит в 1С проведённым, как из
 * приложения); минимальный остаток; справочник сырья.
 */
@Controller
@RequestMapping("/admin/materials")
@RequiredArgsConstructor
public class AdminMaterialController {

    private final IngredientService ingredientService;
    private final StockService stockService;
    private final StockMovementRepository movementRepository;
    private final PurchaseRepository purchaseRepository;
    private final RecipeRepository recipeRepository;
    private final ProductionRequestService requestService;
    private final CurrentUserService currentUserService;
    private final UserRepository userRepository;
    private final OneCStockSyncService oneCStockSyncService;

    static final AdminFormat F = new AdminFormat();

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

    public record Chip(String key, String label, int count, boolean on, String href, boolean alert) {
    }

    public record Opt(String value, String label, boolean on) {
    }

    public record Tile(String href, String cls, String icon, String label, String value, String unit, String sub) {
    }

    private Map<Long, AdminMaterials.Price> prices(Collection<Ingredient> list) {
        List<Long> ids = list.stream().map(Ingredient::getId).toList();
        return ids.isEmpty() ? Map.of() : AdminMaterials.prices(purchaseRepository.findPricedItemsNewestFirst(ids));
    }

    private List<StockMovement> rawMoves(LocalDate from) {
        return movementRepository.findByIngredientIsNotNullAndCreatedAtBetweenOrderByCreatedAtDesc(from.atStartOfDay(), LocalDate.now().plusDays(1).atStartOfDay());
    }

    /** Движение → покупка (приход по покупке), за те же дни. */
    private Map<Long, Purchase> purchasesByMovement(LocalDate from) {
        Map<Long, Purchase> m = new HashMap<>();
        for (Purchase p : purchaseRepository.findByPurchaseDateBetweenOrderByCreatedAtDesc(from.minusDays(3), LocalDate.now().plusDays(1))) {
            for (PurchaseItem it : p.getItems()) if (it.getStockMovement() != null) m.put(it.getStockMovement().getId(), p);
        }
        return m;
    }

    private Map<String, Object> docData(List<Ingredient> active, Long preset) {
        Map<String, Object> d = AdminMaterials.docData(active, preset);
        d.put("canIn", can(Role.ADMIN, Role.ACCOUNTANT));
        d.put("canOff", can(Role.ADMIN, Role.ACCOUNTANT, Role.WAREHOUSE_RAW));
        return d;
    }

    // ================================================================== остатки и журнал

    static final List<String[]> SORTS = List.of(new String[]{"att", "Сначала важные"}, new String[]{"days", "Хватит на меньше дней"},
            new String[]{"name", "По названию"}, new String[]{"sup", "По поставщику"});
    static final List<String[]> PERS = List.of(new String[]{"today", "Период: сегодня"}, new String[]{"yest", "Период: вчера"},
            new String[]{"7", "Период: 7 дней"}, new String[]{"14", "Период: 14 дней"});
    static final List<String[]> TYPES = List.of(new String[]{"all", "Все"}, new String[]{"in", "Приход"}, new String[]{"out", "Выдано в цех"},
            new String[]{"back", "Возврат из цеха"}, new String[]{"off", "Списание"});

    @GetMapping
    @Transactional(readOnly = true)
    public String list(@RequestParam(defaultValue = "stock") String tab, @RequestParam(defaultValue = "") String q,
                       @RequestParam(defaultValue = "att") String sort, @RequestParam(defaultValue = "all") String seg,
                       @RequestParam(defaultValue = "7") String per, @RequestParam(defaultValue = "all") String t,
                       @RequestParam(defaultValue = "60") int limit, Model model) {
        LocalDate today = LocalDate.now();
        List<Ingredient> all = ingredientService.findAll();
        List<Ingredient> active = all.stream().filter(Ingredient::isActive).toList();
        List<ProductionRequest> reqs = requestService.findAll();
        Map<Long, AdminRequests.Need> need = AdminRequests.needNew(reqs);
        List<StockMovement> moves = rawMoves(today.minusDays(Math.max(AdminMaterials.USE_DAYS, 14)));
        Map<Long, BigDecimal> use = AdminMaterials.usage(moves, today);
        List<AdminMaterials.Row> rows = AdminMaterials.rows(active, need, use, prices(active));
        model.addAttribute("tiles", tiles(rows, need, reqs, moves, today));
        model.addAttribute("tab", tab.equals("moves") ? "moves" : "stock");
        model.addAttribute("canIn", can(Role.ADMIN, Role.ACCOUNTANT));
        model.addAttribute("canOff", can(Role.ADMIN, Role.ACCOUNTANT, Role.WAREHOUSE_RAW));
        model.addAttribute("canSync", can(Role.ADMIN));
        model.addAttribute("canEdit", can(Role.ADMIN, Role.WAREHOUSE_RAW, Role.ACCOUNTANT));
        model.addAttribute("docJson", AdminJson.of(docData(active, null)));
        if (tab.equals("moves")) moves(per, t, q, limit, model);
        else stock(rows, all, q, sort, seg, model);
        page(model, "Склад сырья", "остатки, приход и расход", "materials");
        return "admin/warehouse/materials";
    }

    private void stock(List<AdminMaterials.Row> rows, List<Ingredient> all, String q, String sort, String seg, Model model) {
        String[] words = AdminRequests.norm(q).isEmpty() ? new String[0] : AdminRequests.norm(q).split("\\s+");
        List<AdminMaterials.Row> base = rows.stream().filter(r -> {
            String hay = AdminRequests.norm(r.m().getName() + " " + (r.price() != null && r.price().supplier() != null ? r.price().supplier() : ""));
            for (String w : words) if (!hay.contains(w)) return false;
            return true;
        }).toList();
        Map<String, java.util.function.Predicate<AdminMaterials.Row>> segs = new LinkedHashMap<>();
        segs.put("all", r -> true);
        segs.put("short", r -> r.s().key().equals("short"));
        segs.put("low", r -> r.s().key().equals("low") || r.s().key().equals("zero"));
        segs.put("need", r -> r.need() != null && !r.free());
        Map<String, String> labels = Map.of("all", "Все", "short", "Не хватит на заявки", "low", "На исходе", "need", "Нужно для новых заявок");
        String qs = (q.isBlank() ? "" : "&q=" + java.net.URLEncoder.encode(q, StandardCharsets.UTF_8)) + "&sort=" + sort;
        List<Chip> chips = new ArrayList<>();
        segs.forEach((k, f) -> {
            int n = (int) base.stream().filter(f).count();
            chips.add(new Chip(k, labels.get(k), n, seg.equals(k), "/admin/materials?seg=" + k + qs, (k.equals("short") || k.equals("low")) && n > 0));
        });
        List<Ingredient> arch = all.stream().filter(i -> !i.isActive()).sorted(Comparator.comparing(Ingredient::getName, String.CASE_INSENSITIVE_ORDER)).toList();
        boolean showArch = seg.equals("arch");
        List<AdminMaterials.Row> list;
        if (showArch) {
            list = AdminMaterials.rows(arch, Map.of(), Map.of(), prices(arch));
        } else {
            list = new ArrayList<>(base.stream().filter(segs.getOrDefault(seg, segs.get("all"))).toList());
            list.sort(AdminMaterials.sort(sort));
        }
        model.addAttribute("rows", list);
        model.addAttribute("chips", chips);
        model.addAttribute("archCount", arch.size());
        model.addAttribute("archOn", showArch);
        model.addAttribute("sorts", SORTS.stream().map(s -> new Opt(s[0], s[1], s[0].equals(sort))).toList());
        model.addAttribute("q", q);
        model.addAttribute("seg", seg);
        model.addAttribute("sort", sort);
        model.addAttribute("total", rows.size());
    }

    private void moves(String per, String t, String q, int limit, Model model) {
        LocalDate today = LocalDate.now();
        LocalDate from = switch (per) {
            case "today" -> today;
            case "yest" -> today.minusDays(1);
            case "14" -> today.minusDays(13);
            default -> today.minusDays(6);
        };
        LocalDate to = per.equals("yest") ? from : today;
        Function<String, String> who = who();
        Map<Long, Purchase> pur = purchasesByMovement(from);
        String[] words = AdminRequests.norm(q).isEmpty() ? new String[0] : AdminRequests.norm(q).split("\\s+");
        List<AdminMaterials.Move> base = movementRepository.findByIngredientIsNotNullAndCreatedAtBetweenOrderByCreatedAtDesc(from.atStartOfDay(), to.plusDays(1).atStartOfDay())
                .stream().map(x -> AdminMaterials.move(x, pur, who)).filter(m -> AdminMaterials.matches(m, words)).toList();
        Map<String, Integer> cnt = new HashMap<>();
        base.forEach(m -> cnt.merge(m.kind().key(), 1, Integer::sum));
        String qs = "&per=" + per + (q.isBlank() ? "" : "&q=" + java.net.URLEncoder.encode(q, StandardCharsets.UTF_8));
        List<Chip> chips = TYPES.stream().map(x -> new Chip(x[0], x[1], x[0].equals("all") ? base.size() : cnt.getOrDefault(x[0], 0), t.equals(x[0]),
                "/admin/materials?tab=moves&t=" + x[0] + qs, false)).toList();
        List<AdminMaterials.Move> list = base.stream().filter(m -> t.equals("all") || m.kind().key().equals(t)).toList();
        BigDecimal inKg = BigDecimal.ZERO, outKg = BigDecimal.ZERO;
        for (AdminMaterials.Move m : list) {
            if (!"кг".equals(m.unit())) continue;
            if (m.plus()) inKg = inKg.add(m.x().getQuantity());
            else outKg = outKg.add(m.x().getQuantity());
        }
        List<AdminMaterials.Day> days = AdminMaterials.days(list);
        int groups = days.stream().mapToInt(d -> d.groups().size()).sum();
        int lim = Math.max(60, limit);
        List<AdminMaterials.Day> shown = new ArrayList<>();
        int left = lim;
        for (AdminMaterials.Day d : days) {
            if (left <= 0) break;
            List<AdminMaterials.Group> gs = d.groups().size() <= left ? d.groups() : d.groups().subList(0, left);
            shown.add(new AdminMaterials.Day(d.day(), d.title(), d.count(), gs));
            left -= gs.size();
        }
        model.addAttribute("days", shown);
        model.addAttribute("rest", Math.max(0, groups - lim));
        model.addAttribute("moreHref", "/admin/materials?tab=moves&t=" + t + qs + "&limit=" + (lim + 60));
        model.addAttribute("typeChips", chips);
        model.addAttribute("pers", PERS.stream().map(p -> new Opt(p[0], p[1], p[0].equals(per))).toList());
        model.addAttribute("mq", q);
        model.addAttribute("per", per);
        model.addAttribute("t", t);
        model.addAttribute("moveCount", list.size());
        model.addAttribute("inKg", inKg);
        model.addAttribute("outKg", outKg);
    }

    static List<Tile> tiles(List<AdminMaterials.Row> rows, Map<Long, AdminRequests.Need> need, List<ProductionRequest> reqs, List<StockMovement> moves, LocalDate today) {
        List<AdminMaterials.Row> sh = rows.stream().filter(r -> r.s().key().equals("short")).toList();
        List<AdminMaterials.Row> low = rows.stream().filter(r -> r.s().key().equals("low") || r.s().key().equals("zero")).toList();
        List<Long> nr = reqs.stream().filter(r -> r.getStatus() == ProductionRequestStatus.NEW).map(ProductionRequest::getId).sorted().toList();
        String nos = String.join(", ", nr.stream().map(String::valueOf).toList());
        String s1 = !sh.isEmpty() ? String.join(" · ", sh.stream().map(r -> r.m().getName() + " — нужно " + r.q(r.need().q()) + ", есть " + r.q(r.m().getQuantityInStock())).toList())
                + " · заявки №\u00a0" + nos : !nr.isEmpty() ? "сырья хватает на заявки №\u00a0" + nos : "новых заявок нет";
        String s2 = low.isEmpty() ? "всё выше минимального остатка" : String.join(", ", low.stream().limit(4).map(r -> r.m().getName()).toList())
                + (low.size() > 4 ? " и ещё " + (low.size() - 4) : "") + " — ниже минимального остатка";
        BigDecimal kg = BigDecimal.ZERO;
        int n = 0;
        Set<String> rq = new LinkedHashSet<>();
        for (StockMovement x : moves) {
            if (x.isManual() || x.getCreatedAt() == null || !x.getCreatedAt().toLocalDate().equals(today)) continue;
            n++;
            kg = x.getType() == StockMovementType.WRITE_OFF ? kg.add(x.getQuantity()) : kg.subtract(x.getQuantity());
            java.util.regex.Matcher m = AdminMaterials.REQ.matcher(x.getReason() == null ? "" : x.getReason());
            if (m.find()) rq.add(m.group(1));
        }
        String s3 = rq.isEmpty() ? "сегодня ещё не выдавали" : "по " + (rq.size() > 1 ? "заявкам" : "заявке") + " № " + String.join(", ", rq) + " · " + F.count(n, "движение", "движения", "движений");
        return List.of(
                new Tile("/admin/materials?seg=short", sh.isEmpty() ? "" : "kpi--alert", "alert", "Не хватит на новые заявки", String.valueOf(sh.size()), null, s1),
                new Tile("/admin/materials?seg=low", low.isEmpty() ? "" : "kpi--warm", "materials", "На исходе", String.valueOf(low.size()), null, s2),
                new Tile("/admin/materials?tab=moves&per=today&t=out", "", "requests", "Выдано в цех сегодня", F.dec(kg.max(BigDecimal.ZERO), 0), "кг", s3));
    }

    // ================================================================== карточка

    public record Bal(AdminMaterials.Move m, BigDecimal after) {
    }

    public record Use(Product product, BigDecimal q, double share) {
        public String name() {
            return AdminRequests.shortName(product.getName());
        }
    }

    public record InWork(ProductionRequest r, String status, BigDecimal q) {
    }

    public record Bought(Purchase p, BigDecimal q, BigDecimal unitPrice) {
    }

    @GetMapping("/{id:\\d+}")
    @Transactional(readOnly = true)
    public String view(@PathVariable Long id, Model model) {
        Ingredient m;
        try {
            m = ingredientService.findById(id);
        } catch (RuntimeException e) {
            model.addAttribute("missing", id);
            page(model, "Склад сырья", "", "materials");
            return "admin/warehouse/material";
        }
        LocalDate today = LocalDate.now();
        Function<String, String> who = who();
        List<ProductionRequest> reqs = requestService.findAll();
        Map<Long, AdminRequests.Need> need = AdminRequests.needNew(reqs);
        List<StockMovement> moves = movementRepository.findByIngredientIdAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(id, today.minusDays(14).atStartOfDay());
        Map<Long, BigDecimal> use = AdminMaterials.usage(moves, today);
        AdminMaterials.Row row = AdminMaterials.rows(List.of(m), need, use, prices(List.of(m))).get(0);
        Map<Long, Purchase> pur = purchasesByMovement(today.minusDays(14));
        BigDecimal bal = AdminRequests.nz(m.getQuantityInStock());
        List<Bal> hist = new ArrayList<>();
        for (StockMovement x : moves) {
            hist.add(new Bal(AdminMaterials.move(x, pur, who), bal));
            bal = x.getType() == StockMovementType.RECEIPT ? bal.subtract(x.getQuantity()) : bal.add(x.getQuantity());
        }
        List<Use> uses = new ArrayList<>();
        for (Recipe rc : recipeRepository.findAll()) {
            if (rc.getProduct() == null) continue;
            BigDecimal q = rc.getItems().stream().filter(i -> i.getIngredient() != null && i.getIngredient().getId().equals(id))
                    .map(RecipeItem::getQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (q.signum() > 0) uses.add(new Use(rc.getProduct(), q, 0));
        }
        uses.sort(Comparator.comparing(Use::q).reversed());
        double max = uses.isEmpty() ? 1 : uses.get(0).q().doubleValue();
        List<Use> uses2 = uses.stream().map(u -> new Use(u.product(), u.q(), Math.round(u.q().doubleValue() / max * 1000) / 10.0)).toList();
        List<InWork> work = new ArrayList<>();
        for (ProductionRequest r : reqs) {
            if (r.getStatus() != ProductionRequestStatus.NEW && r.getStatus() != ProductionRequestStatus.COLLECTED) continue;
            BigDecimal q = r.getItems().stream().filter(i -> i.getIngredient().getId().equals(id)).map(ProductionRequestItem::getRequiredQuantity)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (q.signum() > 0) work.add(new InWork(r, AdminRequests.badge(r).label().toLowerCase(Locale.ROOT), q));
        }
        work.sort(Comparator.comparing(w -> w.r().getId()));
        List<Bought> bought = new ArrayList<>();
        for (PurchaseItem it : purchaseRepository.findPricedItemsNewestFirst(List.of(id))) {
            if (bought.size() >= 5) break;
            bought.add(new Bought(it.getPurchase(), it.getQuantity(), it.getTotalPrice().divide(it.getQuantity(), 2, RoundingMode.HALF_UP)));
        }
        model.addAttribute("m", m);
        model.addAttribute("row", row);
        model.addAttribute("unit", AdminRequests.unit(m));
        model.addAttribute("hist", hist.size() > 60 ? hist.subList(0, 60) : hist);
        model.addAttribute("histTotal", hist.size());
        model.addAttribute("uses", uses2.size() > 12 ? uses2.subList(0, 12) : uses2);
        model.addAttribute("usesMore", Math.max(0, uses2.size() - 12));
        model.addAttribute("work", work);
        model.addAttribute("bought", bought);
        model.addAttribute("minDays", row.use() != null && row.use().signum() > 0 && AdminRequests.nz(m.getMinThreshold()).signum() > 0
                ? Math.max(1, Math.round(m.getMinThreshold().doubleValue() / row.use().doubleValue())) : null);
        model.addAttribute("canIn", can(Role.ADMIN, Role.ACCOUNTANT));
        model.addAttribute("canOff", can(Role.ADMIN, Role.ACCOUNTANT, Role.WAREHOUSE_RAW));
        model.addAttribute("canEdit", can(Role.ADMIN, Role.WAREHOUSE_RAW, Role.ACCOUNTANT));
        model.addAttribute("canBuy", can(Role.ADMIN, Role.WAREHOUSE_RAW, Role.ACCOUNTANT));
        model.addAttribute("docJson", AdminJson.of(docData(ingredientService.findAllActive(), id)));
        page(model, "Склад сырья", "", "materials");
        return "admin/warehouse/material";
    }

    @PostMapping("/{id:\\d+}/min")
    public String saveMin(@PathVariable Long id, @RequestParam(required = false) String min, RedirectAttributes ra) {
        try {
            if (!can(Role.ADMIN, Role.WAREHOUSE_RAW, Role.ACCOUNTANT)) throw new IllegalStateException("Минимальный остаток меняют склад сырья, бухгалтер и администратор");
            BigDecimal v = num(min);
            if (v != null && v.signum() < 0) throw new IllegalArgumentException("Минимальный остаток не может быть меньше нуля");
            Ingredient m = ingredientService.findById(id);
            m.setMinThreshold(v == null ? BigDecimal.ZERO : v);
            ingredientService.save(m);
            ok(ra, "Минимальный остаток «" + m.getName() + "»: " + (v == null || v.signum() == 0 ? "не следить" : AdminRequests.qu(v, AdminRequests.unit(m))));
        } catch (RuntimeException e) {
            fail(ra, e);
        }
        return "redirect:/admin/materials/" + id;
    }

    // ================================================================== документ «Поступление / Списание»

    @PostMapping("/doc")
    public String saveDoc(HttpServletRequest req, RedirectAttributes ra) {
        boolean off = "out".equals(req.getParameter("type")) || "off".equals(req.getParameter("type"));
        String back = Optional.ofNullable(req.getParameter("back")).filter(s -> s.startsWith("/admin/materials")).orElse("/admin/materials?tab=moves");
        try {
            if (off ? !can(Role.ADMIN, Role.ACCOUNTANT, Role.WAREHOUSE_RAW) : !can(Role.ADMIN, Role.ACCOUNTANT)) {
                throw new IllegalStateException((off ? "Списание" : "Поступление") + " — нет прав у вашей роли");
            }
            LocalDate date = date(req.getParameter("date"), LocalDate.now());
            String reason = Optional.ofNullable(req.getParameter("reason")).orElse("").trim();
            String[] ids = arr(req, "item"), qs = arr(req, "qty");
            List<StockService.DocumentLine> doc = new ArrayList<>();
            Set<Long> seen = new HashSet<>();
            for (int i = 0; i < ids.length; i++) {
                Long id = AdminSupport.id(ids[i]);
                BigDecimal q = num(at(qs, i));
                if (id == null && q == null) continue;
                if (id == null || q == null || q.signum() <= 0) throw new IllegalArgumentException("Проверьте строки: сырьё и количество больше нуля");
                if (!seen.add(id)) throw new IllegalArgumentException("Сырьё дважды в документе — сложите в одну строку");
                doc.add(new StockService.DocumentLine(ingredientService.findById(id), null, q.setScale(3, RoundingMode.HALF_UP)));
            }
            StockDocument d = stockService.createDocument(off ? StockMovementType.WRITE_OFF : StockMovementType.RECEIPT, false, date, reason, doc,
                    currentUserService.username().orElse("admin"));
            ok(ra, (off ? "Списание" : "Поступление") + " № " + d.getId() + " проведено: " + F.count(doc.size(), "позиция", "позиции", "позиций") + ". Документ уйдёт в 1С.");
        } catch (RuntimeException e) {
            fail(ra, e);
        }
        return "redirect:" + back;
    }

    @PostMapping("/sync-1c")
    public String sync(RedirectAttributes ra) {
        try {
            if (!can(Role.ADMIN)) throw new IllegalStateException("Сверку с 1С запускает администратор");
            OneCStockSyncService.SyncResult r = oneCStockSyncService.sync();
            ok(ra, "Сверили с 1С: сырья обновлено " + r.ingredientsUpdated() + ", товаров " + r.productsUpdated());
        } catch (RuntimeException e) {
            fail(ra, e);
        }
        return "redirect:/admin/materials";
    }

    /** Старый адрес журнала. */
    @GetMapping("/movements")
    public String movementsOld() {
        return "redirect:/admin/materials?tab=moves";
    }

    // ================================================================== справочник сырья

    @GetMapping({"/new", "/{id:\\d+}/edit"})
    @Transactional(readOnly = true)
    public String ingredientForm(@PathVariable(required = false) Long id, Model model) {
        Ingredient ing = id == null ? new Ingredient() : ingredientService.findById(id);
        if (id == null) ing.setUnit("кг");
        model.addAttribute("ing", ing);
        model.addAttribute("errors", List.of());
        page(model, "Склад сырья", "", "materials");
        return "admin/warehouse/ingredient";
    }

    @PostMapping({"", "/{id:\\d+}"})
    public String saveIngredient(@PathVariable(required = false) Long id, @RequestParam String name, @RequestParam String unit,
                                 @RequestParam(required = false) String minThreshold, @RequestParam(defaultValue = "false") boolean active,
                                 Model model, RedirectAttributes ra) {
        Ingredient ing = id == null ? new Ingredient() : ingredientService.findById(id);
        ing.setName(name == null ? "" : name.trim());
        ing.setUnit(unit == null || unit.isBlank() ? "кг" : unit.trim());
        BigDecimal min = num(minThreshold);
        ing.setMinThreshold(min == null ? BigDecimal.ZERO : min);
        ing.setActive(id == null || active);
        List<String> errors = new ArrayList<>();
        if (!can(Role.ADMIN, Role.WAREHOUSE_RAW, Role.ACCOUNTANT)) errors.add("Справочник сырья меняют склад сырья, бухгалтер и администратор.");
        if (ing.getName().isEmpty()) errors.add("Укажите название сырья.");
        if (ing.getMinThreshold().signum() < 0) errors.add("Минимальный остаток не может быть меньше нуля.");
        if (errors.isEmpty()) {
            try {
                Ingredient saved = ingredientService.save(ing);
                ok(ra, "Сырьё «" + ing.getName() + "» сохранено");
                return "redirect:/admin/materials/" + (saved != null && saved.getId() != null ? saved.getId() : "");
            } catch (RuntimeException e) {
                errors.add(e.getMessage() != null && e.getMessage().contains("constraint") ? "Такое сырьё уже есть в справочнике." : e.getMessage());
            }
        }
        model.addAttribute("ing", ing);
        model.addAttribute("errors", errors);
        page(model, "Склад сырья", "", "materials");
        return "admin/warehouse/ingredient";
    }
}
