package com.confectionery.crm.admin;

import com.confectionery.crm.api.dto.ProductionCostDto;
import com.confectionery.crm.domain.*;
import com.confectionery.crm.repository.*;
import com.confectionery.crm.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;

import static com.confectionery.crm.admin.AdminSupport.*;

/**
 * Товары по макету (часть 3): каталог (/admin/products) — поиск по названию и коду 1С, отборы, группы производства,
 * порядок, чего не хватает в карточке, «Корпуса из 1С»; карточка товара (/admin/products/{id}) — склад, заказы в
 * работе, выпуск и себестоимость, витрина, рецептура и техкарта, 1С; новый товар и изменение (/new, /{id}/edit) —
 * менять товары может только администратор. Фото товаров — альбом для сайта (/admin/photos).
 */
@Controller
@RequiredArgsConstructor
public class AdminProductController {

    private final ProductService productService;
    private final ProductRepository productRepository;
    private final ProductPhotoService photoService;
    private final OrderPriceFillService orderPriceFillService;
    private final CurrentUserService currentUserService;
    private final PromoPageService promoPageService;
    private final RecipeRepository recipeRepository;
    private final TechCardRepository techCardRepository;
    private final OrderRepository orderRepository;
    private final StockMovementRepository movementRepository;
    private final ProductionRequestRepository requestRepository;
    private final ProductionCostService costService;
    private final UserRepository userRepository;
    private final OneCDepartmentImportService departmentImportService;

    static final AdminFormat F = new AdminFormat();
    static final String NB = AdminFormat.NB;
    /** Сколько дней заявок смотреть для «Выпуск и себестоимость». */
    static final int MADE_DAYS = 90;

    public record Chip(String key, String label, int count, boolean on, String href, boolean alert) {
    }

    public record Opt(String value, String label, boolean on) {
    }

    private boolean isAdmin() {
        return currentUserService.hasRole(Role.ADMIN);
    }

    private boolean can(Role... roles) {
        return currentUserService.hasRole(roles);
    }

    /** Виден ли роли раздел меню — тогда и ссылка на него. */
    private boolean sees(String section) {
        Role role = currentUserService.role().orElse(null);
        AdminSections.Section s = AdminSections.byKey(section);
        return role != null && s != null && s.visibleTo(role);
    }

    private Map<Long, Recipe> recipes() {
        Map<Long, Recipe> m = new HashMap<>();
        for (Recipe r : recipeRepository.findAll()) if (r.getProduct() != null) m.put(r.getProduct().getId(), r);
        return m;
    }

    // ================================================================== каталог

    @GetMapping("/admin/products")
    @Transactional(readOnly = true)
    public String list(@RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "all") String seg,
                       @RequestParam(defaultValue = "all") String grp, @RequestParam(defaultValue = "grp") String sort, Model model) {
        List<Product> all = productRepository.findAll();
        Map<Long, Recipe> recipes = recipes();
        Map<Long, AdminStock.Res> res = AdminStock.reserve(orderRepository.findByStatusInOrderByCreatedAtAsc(AdminStock.OPEN), LocalDate.now());
        Map<Long, AdminStock.Row> st = new HashMap<>();
        AdminStock.rows(all, res, Map.of(), List.of(), Map.of(), Map.of()).forEach(r -> st.put(r.p().getId(), r));

        String nq = AdminRequests.norm(q);
        String[] words = nq.isEmpty() ? new String[0] : nq.split("\\s+");
        List<Product> base = all.stream().filter(p -> AdminProducts.matches(p, words)).toList();
        String segIn = seg, sortIn = sort;
        if (AdminProducts.SEGS.stream().noneMatch(s -> s[0].equals(segIn))) seg = "all";
        if (AdminProducts.SORTS.stream().noneMatch(s -> s[0].equals(sortIn))) sort = "grp";
        String qs = (q.isBlank() ? "" : "&q=" + URLEncoder.encode(q, StandardCharsets.UTF_8)) + (sort.equals("grp") ? "" : "&sort=" + sort);
        String curSeg = seg;
        List<Chip> segChips = new ArrayList<>();
        for (String[] s : AdminProducts.SEGS) {
            int n = (int) base.stream().filter(p -> AdminProducts.inSeg(p, s[0])).count();
            segChips.add(new Chip(s[0], s[1], n, s[0].equals(curSeg), "/admin/products?seg=" + s[0] + qs, s[0].equals("nobox") && n > 0));
        }
        List<Product> bySeg = base.stream().filter(p -> AdminProducts.inSeg(p, curSeg)).toList();
        Map<String, Integer> gc = new TreeMap<>(AdminProducts.groupOrder());
        bySeg.forEach(p -> gc.merge(AdminProducts.group(p), 1, Integer::sum));
        if (!grp.equals("all") && !gc.containsKey(grp)) grp = "all";
        String curGrp = grp, segQs = "&seg=" + curSeg + qs;
        List<Chip> grpChips = new ArrayList<>();
        grpChips.add(new Chip("all", "Все группы", bySeg.size(), curGrp.equals("all"), "/admin/products?grp=all" + segQs, false));
        gc.forEach((g, n) -> grpChips.add(new Chip(g, g, n, g.equals(curGrp), "/admin/products?grp=" + URLEncoder.encode(g, StandardCharsets.UTF_8) + segQs, false)));

        List<AdminProducts.Row> rows = new ArrayList<>(bySeg.stream().filter(p -> curGrp.equals("all") || AdminProducts.group(p).equals(curGrp))
                .map(p -> new AdminProducts.Row(p, p.isSemiFinished() ? null : st.get(p.getId()), AdminProducts.issues(p, recipes.get(p.getId())), AdminProducts.site(p)))
                .toList());
        rows.sort(AdminProducts.sort(sort));
        String curSort = sort;
        long active = all.stream().filter(Product::isActive).count(), pf = all.stream().filter(p -> p.isActive() && p.isSemiFinished()).count();
        model.addAttribute("groups", AdminProducts.grouped(rows, sort.equals("grp")));
        model.addAttribute("total", rows.size());
        model.addAttribute("segChips", segChips);
        model.addAttribute("grpChips", grpChips);
        model.addAttribute("sorts", AdminProducts.SORTS.stream().map(s -> new Opt(s[0], s[1], s[0].equals(curSort))).toList());
        model.addAttribute("q", q);
        model.addAttribute("seg", curSeg);
        model.addAttribute("grp", curGrp);
        model.addAttribute("sort", curSort);
        model.addAttribute("canEdit", isAdmin());
        model.addAttribute("newHref", "/admin/products/new" + (curGrp.equals("all") ? "" : "?grp=" + URLEncoder.encode(curGrp, StandardCharsets.UTF_8)));
        model.addAttribute("cnt", "Товаров: " + active + " (из них полуфабрикатов " + pf + "), в архиве " + (all.size() - active)
                + ". Замечания — чего не хватает в карточке: объёма коробки и веса для накладной «Объём и масса», фото для сайта, рецептуры для заявок, связи и корпуса для 1С.");
        page(model, "Товары", "каталог: цены, упаковка, витрина и 1С", "products");
        return "admin/products/list";
    }

    @PostMapping("/admin/products/departments-1c")
    public String importDepartments(@RequestParam(required = false) String next, RedirectAttributes ra) {
        try {
            if (!isAdmin()) throw new IllegalStateException("Корпуса из 1С загружает администратор");
            OneCDepartmentImportService.ImportResult r = departmentImportService.importAll();
            ok(ra, "Подразделение обновлено у " + F.count(r.updated(), "товара", "товаров", "товаров") + ". Без истории документов в 1С: "
                    + r.skippedNoHistory() + ". Не связаны с 1С: " + r.skippedNoOneCMatch() + ".");
        } catch (RuntimeException e) {
            fail(ra, e);
        }
        return AdminSupport.back(next, "/admin/products");
    }

    // ================================================================== карточка

    @GetMapping("/admin/products/{id:\\d+}")
    @Transactional(readOnly = true)
    public String view(@PathVariable Long id, Model model) {
        page(model, "Товары", "", "products");
        Product p = productRepository.findById(id).orElse(null);
        if (p == null) {
            model.addAttribute("missing", true);
            model.addAttribute("docJson", null);
            return "admin/products/view";
        }
        boolean pf = p.isSemiFinished(), canCost = can(Role.ADMIN, Role.ACCOUNTANT), canDoc = can(Role.ADMIN, Role.WAREHOUSE_FG, Role.ACCOUNTANT);
        LocalDate today = LocalDate.now(), from = today.minusDays(13);
        Recipe rc = recipeRepository.findByProductId(id).orElse(null);
        List<ProductionRequest> reqs = requestRepository.findByDocumentDateBetween(today.minusDays(MADE_DAYS - 1), today.plusDays(30));

        // выпуск: строки заявок с товаром, себестоимость — по 8 последним
        List<AdminProducts.Made> made = AdminProducts.made(reqs, id);
        List<AdminProducts.Made> shown = new ArrayList<>(made.subList(0, Math.min(8, made.size())));
        if (canCost) {
            Map<Long, ProductionCostDto.RequestDetail> cost = new HashMap<>();
            for (int i = 0; i < shown.size(); i++) {
                AdminProducts.Made m = shown.get(i);
                ProductionCostDto.RequestDetail d = cost.computeIfAbsent(m.r().getId(), rid -> {
                    try {
                        return costService.request(rid, true);
                    } catch (RuntimeException e) {
                        return null;
                    }
                });
                int idx = m.r().getLines().indexOf(m.l());
                if (d != null && idx >= 0 && idx < d.request().lines().size()) shown.set(i, new AdminProducts.Made(m.r(), m.l(), d.request().lines().get(idx)));
            }
        }
        AdminProducts.Made lastC = AdminProducts.lastCost(shown);
        AdminProducts.Made lastGot = made.stream().filter(m -> m.l().isReceived()).findFirst().orElse(null);

        List<AdminProducts.Kpi> kpis = new ArrayList<>();
        if (pf) {
            BigDecimal kg = AdminProducts.batchKg(rc);
            kpis.add(new AdminProducts.Kpi("Выход с замеса", rc != null && AdminProducts.hasRecipe(rc) ? F.num(rc.getYieldQuantity()) : "—",
                    rc != null && AdminProducts.hasRecipe(rc) ? AdminProducts.unit(p) : null,
                    kg != null ? "закладка " + F.fix(kg, 3) + NB + "кг" : "рецептура не заполнена", ""));
            kpis.add(new AdminProducts.Kpi("Последний выпуск", made.isEmpty() ? "—" : F.dm(made.get(0).r().getDocumentDate()), null,
                    made.isEmpty() ? "за три месяца не выпускали" : "заявка № " + made.get(0).r().getId(), ""));
            if (canCost) kpis.add(costKpi(lastC, rc));
            else {
                BigDecimal g = AdminProducts.perUnitGrams(rc);
                kpis.add(new AdminProducts.Kpi("Тесто на 1 " + AdminProducts.unit(p), g != null ? F.num(g) : "—", g != null ? "г" : null, "по рецептуре", ""));
            }
            kpis.add(new AdminProducts.Kpi("Где хранится", "склад сырья", null, "остаток зеркалится в одноимённое сырьё — его и пишут в рецептуру товара", ""));
            model.addAttribute("docJson", null);                    // полуфабрикат — на складе сырья, не здесь
        } else {
            List<Order> open = orderRepository.findByStatusInOrderByCreatedAtAsc(AdminStock.OPEN);
            Map<Long, Recipe> one = rc == null ? Map.of() : Map.of(id, rc);
            List<AdminStock.Row> rr = AdminStock.rows(List.of(p), AdminStock.reserve(open, today), AdminStock.inWork(reqs), List.of(), Map.of(), one);
            BigDecimal stock = AdminStock.nz(p.getQuantityInStock());
            AdminStock.Row x = rr.isEmpty() ? new AdminStock.Row(p, stock, null, stock, stock.signum() <= 0 ? "zero" : "ok", null, null, null, null) : rr.get(0);
            String boxes = x.boxes();
            kpis.add(new AdminProducts.Kpi("Остаток", F.num(x.stock()), "шт",
                    x.stock().signum() > 0 ? (boxes.isEmpty() || boxes.equals("неполная коробка") ? "" : boxes + " · ") + "на " + F.money(x.stock().multiply(AdminStock.nz(p.getPrice())))
                            : "нет на складе", ""));
            boolean sh = x.state().equals("short");
            String resSub = x.rq().signum() <= 0 ? "в открытых заказах нет"
                    : sh ? "<b>не хватает " + F.num(x.free().negate()) + "</b>" + (x.work() != null ? " · в заявке " + x.work().nos() + " — " + F.num(x.work().q()) + NB + "шт" : " · в производстве нет")
                    : "свободно " + F.num(x.free());
            kpis.add(new AdminProducts.Kpi("В заказах", x.rq().signum() > 0 ? F.num(x.rq()) : "—", x.rq().signum() > 0 ? "шт" : null, resSub,
                    sh ? (x.covered() ? "kpi--warm" : "kpi--alert") : ""));
            if (canCost) kpis.add(costKpi(lastC, rc));
            else kpis.add(new AdminProducts.Kpi("Последний выпуск", lastGot == null ? "—" : F.dm(lastGot.r().getDocumentDate()), null,
                    lastGot == null ? (made.isEmpty() ? "за три месяца не выпускали" : "в работе: заявка № " + made.get(0).r().getId())
                            : "заявка № " + lastGot.r().getId() + " · оприходовано " + F.num(lastGot.l().getReceivedQuantity()) + NB + "шт", ""));
            BigDecimal sold = BigDecimal.ZERO, soldSum = BigDecimal.ZERO;
            for (Order o : orderRepository.findByShippedAtBetween(from.atStartOfDay(), today.plusDays(1).atStartOfDay())) {
                if (!o.getStatus().isShipped()) continue;
                for (OrderItem i : o.getItems()) {
                    if (i.getProduct() == null || !id.equals(i.getProduct().getId())) continue;
                    BigDecimal qn = AdminStock.nz(i.getQuantity());
                    sold = sold.add(qn);
                    soldSum = soldSum.add(qn.multiply(i.getPrice() != null ? i.getPrice() : AdminStock.nz(p.getPrice())));
                }
            }
            kpis.add(new AdminProducts.Kpi("Выдано за 14 дней", F.num(sold), "шт", sold.signum() > 0 ? "на " + F.money(soldSum) + " по выданным заказам" : "выдач не было", ""));

            // склад: движения за две недели — журнал и выдача заказов через CRM
            Function<String, String> who = AdminStock.who(userRepository);
            List<AdminStock.Move> moves = new ArrayList<>();
            for (StockMovement mv : movementRepository.findByProductIdAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(id, from.atStartOfDay())) {
                moves.add(AdminStock.move(mv, who));
            }
            for (Order o : orderRepository.findByShippedAtBetween(from.atStartOfDay(), today.plusDays(1).atStartOfDay())) {
                if (AdminStock.movesStock(o)) AdminStock.shipped(o, who).stream().filter(m -> id.equals(m.p().getId())).forEach(moves::add);
            }
            moves.sort(Comparator.comparing(AdminStock.Move::at).reversed());
            model.addAttribute("moves", moves.subList(0, Math.min(12, moves.size())));
            model.addAttribute("movesTotal", moves.size());
            model.addAttribute("movesHref", "/admin/stock?tab=moves&per=14&q=" + URLEncoder.encode(p.getName(), StandardCharsets.UTF_8));
            List<AdminProducts.OrderRow> ord = AdminProducts.orders(open, id);
            model.addAttribute("orders", ord);
            model.addAttribute("ordersTotal", ord.stream().map(AdminProducts.OrderRow::q).reduce(BigDecimal.ZERO, BigDecimal::add));
            Map<String, Object> doc = AdminStock.docData(List.of(x), canDoc, canDoc);
            doc.put("preset", id);
            model.addAttribute("docJson", AdminJson.of(doc));
        }
        model.addAttribute("kpis", kpis);
        model.addAttribute("made", shown);
        model.addAttribute("madeEmpty", AdminProducts.hasRecipe(rc) ? "За три месяца в заявках не было." : "Рецептуры нет — в заявку не добавить.");
        model.addAttribute("madeHref", canCost ? "/admin/production/requests/" : sees("requests") ? "/admin/requests/" : null);

        TechCard tc = techCardRepository.findByProductId(id).orElse(null);
        BigDecimal box = AdminOrders.boxUnits(p);
        model.addAttribute("p", p);
        model.addAttribute("pf", pf);
        model.addAttribute("site", AdminProducts.site(p));
        model.addAttribute("hidden", AdminProducts.hiddenNow(p));
        model.addAttribute("photo", AdminOrders.photo(p));
        model.addAttribute("rc", AdminProducts.hasRecipe(rc) ? rc : null);
        model.addAttribute("rcKg", AdminProducts.batchKg(rc));
        model.addAttribute("rcPer", AdminProducts.perUnitGrams(rc));
        model.addAttribute("tcDraft", tc != null && Boolean.TRUE.equals(tc.getDraft()));
        model.addAttribute("box", box);
        model.addAttribute("boxAuto", box != null && (p.getUnitsPerBox() == null || p.getUnitsPerBox().signum() <= 0));
        model.addAttribute("dep", AdminProducts.department(p));
        model.addAttribute("hidTo", p.getHiddenFromClientsUntil());
        model.addAttribute("hidNext", p.getHiddenFromClientsUntil() == null ? null : p.getHiddenFromClientsUntil().plusDays(1));
        model.addAttribute("m1", today.plusMonths(1));
        model.addAttribute("m2", today.plusMonths(2));
        model.addAttribute("today", today);
        model.addAttribute("canEdit", isAdmin());
        model.addAttribute("canDoc", canDoc);
        model.addAttribute("canMake", can(Role.ADMIN, Role.ACCOUNTANT, Role.TECHNOLOGIST) && rc != null && rc.getYieldQuantity() != null && rc.getYieldQuantity().signum() > 0);
        model.addAttribute("canCost", canCost);
        model.addAttribute("used", isAdmin() && productService.hasHistory(id));
        model.addAttribute("seesRecipes", sees("recipes"));
        model.addAttribute("seesTech", sees("techcards"));
        model.addAttribute("seesPhotos", sees("photos"));
        model.addAttribute("seesStock", sees("stock"));
        return "admin/products/view";
    }

    private static AdminProducts.Kpi costKpi(AdminProducts.Made c, Recipe rc) {
        if (c == null) {
            return new AdminProducts.Kpi("Себестоимость 1 шт", "—", null,
                    AdminProducts.hasRecipe(rc) ? "за три месяца не выпускали" : "нет рецептуры — не посчитать", "");
        }
        BigDecimal mk = c.markup();
        return new AdminProducts.Kpi("Себестоимость 1 шт", F.dec(c.unitCost(), 0), "₸",
                (c.l().isReceived() ? "выпуск " : "по плану, ") + F.dm(c.r().getDocumentDate()) + ", заявка № " + c.r().getId()
                        + (mk != null ? " · наценка <b" + (mk.signum() < 0 ? " class=\"pay-bad\"" : "") + ">" + AdminProduction.pct(mk, 0) + "</b>" : ""),
                mk != null && mk.signum() < 0 ? "kpi--warm" : "");
    }

    // ================================================================== форма

    @GetMapping("/admin/products/new")
    @Transactional(readOnly = true)
    public String newForm(@RequestParam(required = false) String grp, Model model, RedirectAttributes ra) {
        if (!isAdmin()) {
            fail(ra, new IllegalStateException("Новый товар заводит администратор"));
            return "redirect:/admin/products";
        }
        Product p = new Product();
        p.setUnit("шт");
        if (grp != null && !grp.isBlank() && !grp.equals("all") && !grp.equals(AdminProducts.NO_GROUP)) p.setGroup(grp);
        return form(p, AdminProducts.Form.of(p), new LinkedHashMap<>(), model);
    }

    @GetMapping("/admin/products/{id:\\d+}/edit")
    @Transactional(readOnly = true)
    public String editForm(@PathVariable Long id, Model model, RedirectAttributes ra) {
        if (!isAdmin()) {
            fail(ra, new IllegalStateException("Менять товары может администратор"));
            return "redirect:/admin/products/" + id;
        }
        Product p = productRepository.findById(id).orElse(null);
        if (p == null) {
            fail(ra, new IllegalArgumentException("Такого товара нет"));
            return "redirect:/admin/products";
        }
        return form(p, AdminProducts.Form.of(p), new LinkedHashMap<>(), model);
    }

    private String form(Product p, AdminProducts.Form f, Map<String, String> errors, Model model) {
        List<Product> all = productRepository.findAll();
        Set<String> cats = new TreeSet<>(AdminProducts.RU::compare);
        Set<String> groups = new TreeSet<>(AdminProducts.RU::compare);
        for (Product x : all) {
            if (x.getCategory() != null && !x.getCategory().isBlank()) cats.add(x.getCategory().trim());
            if (x.getGroup() != null && !x.getGroup().isBlank()) groups.add(x.getGroup().trim());
        }
        if (!f.group().isBlank() && !f.group().equals("__new")) groups.add(f.group());
        List<String> units = new ArrayList<>(List.of("шт", "кг"));
        if (!units.contains(f.unit())) units.add(f.unit());
        Map<String, String> deps = new LinkedHashMap<>(com.confectionery.crm.service.OneCDepartments.NAMES);
        if (!f.dep().isEmpty() && !deps.containsKey(f.dep())) deps.put(f.dep(), "подразделение 1С " + f.dep());
        // эталон группы: кто сейчас (для подсказки «он перестанет им быть»)
        Map<String, String> refs = new LinkedHashMap<>();
        for (Product x : all) {
            if (x.isMassReferenceProduct() && x.getGroup() != null && !Objects.equals(x.getId(), p.getId())) refs.put(x.getGroup().trim(), x.getName());
        }
        Map<String, Object> js = new LinkedHashMap<>();
        js.put("id", p.getId());
        js.put("names", all.stream().filter(x -> !Objects.equals(x.getId(), p.getId()) && x.getName() != null).map(x -> x.getName().trim().toLowerCase(Locale.ROOT)).toList());
        js.put("refs", refs);
        js.put("photo", p.getId() == null ? null : AdminOrders.photo(p));
        js.put("tried", !errors.isEmpty());
        js.put("saveError", errors.get("save"));
        model.addAttribute("p", p);
        model.addAttribute("f", f);
        model.addAttribute("errors", errors);
        model.addAttribute("errorList", new ArrayList<>(new LinkedHashSet<>(errors.values())));
        model.addAttribute("cats", cats);
        model.addAttribute("groupList", groups);
        model.addAttribute("units", units);
        model.addAttribute("deps", deps);
        model.addAttribute("isNew", p.getId() == null);
        model.addAttribute("formJson", AdminJson.of(js));
        page(model, "Товары", "", "products");
        return "admin/products/form";
    }

    @PostMapping({"/admin/products", "/admin/products/{id:\\d+}"})
    public String save(@PathVariable(required = false) Long id, @RequestParam Map<String, String> f, Model model, RedirectAttributes ra) {
        if (!isAdmin()) {
            fail(ra, new IllegalStateException("Менять товары может администратор"));
            return id == null ? "redirect:/admin/products" : "redirect:/admin/products/" + id;
        }
        Product p = id == null ? new Product() : productService.findById(id);
        boolean semi = id == null ? "true".equals(f.get("semiFinished")) : p.isSemiFinished();
        AdminProducts.Form form = AdminProducts.Form.of(f, semi);
        List<Product> all = productRepository.findAll();
        LinkedHashMap<String, String> errors = AdminProducts.errors(form, id, p.getOneCDepartmentKey(), all);
        if (!errors.isEmpty()) return form(p, form, errors, model);
        BigDecimal oldPrice = p.getPrice();
        p.setName(form.name().trim());
        p.setGroup(form.groupValue().trim());
        p.setUnit(form.unit());
        p.setDescription(form.description().isBlank() ? null : form.description());
        p.setOneCDepartmentKey(form.dep().isEmpty() ? null : form.dep());
        p.setNetWeightGrams(AdminProducts.num(form.netG()));
        if (id == null) {
            p.setSemiFinished(semi);
            if (semi) p.setPrice(BigDecimal.ZERO);
        }
        if (!semi) {
            p.setCategory(form.category().isBlank() ? null : form.category());
            p.setPrice(AdminProducts.num(form.price()).setScale(2, RoundingMode.HALF_UP));
            p.setUnitWeightKg(AdminProducts.num(form.weight()));
            p.setUnitsPerBox(AdminProducts.num(form.box()));
            p.setBoxVolumeM3(AdminProducts.num(form.vol()));
            p.setUnitsPerBox2(AdminProducts.num(form.box2()));
            p.setBoxVolume2M3(AdminProducts.num(form.vol2()));
            p.setMassReferenceProduct(form.mref());
        }
        try {
            if (!semi && form.mref()) {
                // эталон в группе один: у остальных товаров группы отметка снимается
                for (Product x : all) {
                    if (!Objects.equals(x.getId(), id) && x.isMassReferenceProduct() && x.getGroup() != null && x.getGroup().trim().equalsIgnoreCase(p.getGroup())) {
                        x.setMassReferenceProduct(false);
                        productRepository.save(x);
                    }
                }
            }
            Product saved = productService.save(p);
            String msg;
            if (id == null) {
                msg = "Товар «" + saved.getName() + "» создан" + (semi ? " — полуфабрикат: в рецептурах и заявках" : " — его уже можно добавить в заказ");
            } else {
                msg = "Карточка «" + saved.getName() + "» сохранена";
                if (!semi && oldPrice != null && saved.getPrice().compareTo(oldPrice) != 0) {
                    int fixed = orderPriceFillService.afterPriceChange(null, id);
                    msg += ". Новая цена — для новых заказов, в оформленных осталась прежняя"
                            + (fixed > 0 ? " (в открытых заказах без цены подставлена новая: " + F.count(fixed, "строка", "строки", "строк") + ")" : "");
                }
            }
            ok(ra, msg);
            // «Сохранить и выйти» из окна о несохранённом — туда, куда шли
            String next = f.get("next");
            if (next != null && next.startsWith("/admin/") && !next.startsWith("//")) return "redirect:" + next;
            return "redirect:/admin/products/" + saved.getId();
        } catch (RuntimeException e) {
            errors.put("save", e.getMessage() == null ? "Не получилось сохранить — попробуйте ещё раз." : e.getMessage());
            return form(p, form, errors, model);
        }
    }

    // ================================================================== витрина, архив

    @PostMapping("/admin/products/{id:\\d+}/visibility")
    public String visibility(@PathVariable Long id, @RequestParam boolean hidden, @RequestParam(required = false) String until,
                             RedirectAttributes ra) {
        try {
            if (!isAdmin()) throw new IllegalStateException("Скрыть товар может администратор");
            LocalDate u = until == null || until.isBlank() ? null : LocalDate.parse(until);
            Product p = productService.setHiddenFromClients(id, hidden, u);
            ok(ra, hidden ? "«" + p.getName() + "» скрыт от клиентов" + (u != null ? " по " + F.dml(u) : "")
                    : "«" + p.getName() + "» снова виден клиентам");
        } catch (RuntimeException e) {
            fail(ra, e);
        }
        return "redirect:/admin/products/" + id;
    }

    @PostMapping("/admin/products/{id:\\d+}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        try {
            if (!isAdmin()) throw new IllegalStateException("Удалить товар может администратор");
            String name = productService.findById(id).getName();
            ProductService.DeleteOutcome out = productService.delete(id);
            if (out == ProductService.DeleteOutcome.ARCHIVED) {
                ok(ra, "«" + name + "» в архиве — на сайте и в новых заказах его нет");
                return "redirect:/admin/products/" + id;
            }
            ok(ra, "«" + name + "» удалён");
            return "redirect:/admin/products";
        } catch (RuntimeException e) {
            fail(ra, e);
            return "redirect:/admin/products/" + id;
        }
    }

    @PostMapping("/admin/products/{id:\\d+}/restore")
    public String restore(@PathVariable Long id, RedirectAttributes ra) {
        try {
            if (!isAdmin()) throw new IllegalStateException("Вернуть товар может администратор");
            productService.restore(id);
            ok(ra, "«" + productService.findById(id).getName() + "» вернули из архива — снова в каталоге и в заказах");
        } catch (RuntimeException e) {
            fail(ra, e);
        }
        return "redirect:/admin/products/" + id;
    }

    // ------------------------------------------------------------------ фото товаров

    @GetMapping("/admin/photos")
    @Transactional(readOnly = true)
    public String photos(@RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "0") int empty, Model model) {
        String nq = AdminOrderController.norm(q);
        List<Product> rows = productService.findAllActiveForManagement().stream()
                .filter(p -> nq.isEmpty() || AdminOrderController.norm(p.getName()).contains(nq))
                .filter(p -> empty == 0 || p.getPhotos().isEmpty() && (p.getImageUrl() == null || p.getImageUrl().isBlank()))
                .sorted(Comparator.comparing(Product::getName, String.CASE_INSENSITIVE_ORDER)).toList();
        model.addAttribute("rows", rows);
        model.addAttribute("q", q);
        model.addAttribute("empty", empty == 1);
        page(model, "Фото товаров", "обложка и альбом для сайта и магазина", "photos");
        return "admin/photos/list";
    }

    @GetMapping("/admin/photos/{id:\\d+}")
    @Transactional(readOnly = true)
    public String productPhotos(@PathVariable Long id, @RequestParam(required = false) Long sel, Model model) {
        Product p = productService.findById(id);
        List<ProductPhoto> album = new ArrayList<>(p.getPhotos());
        album.sort(Comparator.comparingInt(ProductPhoto::getPosition));
        model.addAttribute("p", p);
        model.addAttribute("album", album);
        model.addAttribute("card", promoPageService.card(p));
        model.addAttribute("sel", sel);
        // слоты рядами по 6, как в макете; хотя бы один пустой — «Добавить»
        model.addAttribute("emptySlots", Math.max(1, (6 - album.size() % 6) % 6));
        page(model, "Фото товаров", p.getName(), "photos");
        return "admin/photos/product";
    }

    @PostMapping("/admin/photos/{id:\\d+}/add")
    public String addPhotos(@PathVariable Long id, @RequestParam("files") List<MultipartFile> files, RedirectAttributes ra) {
        try {
            List<MultipartFile> real = files.stream().filter(f -> !f.isEmpty()).toList();
            if (real.isEmpty()) throw new IllegalArgumentException("Выберите фото");
            photoService.add(id, real);
            ok(ra, "Добавлено фото: " + real.size());
        } catch (RuntimeException e) {
            fail(ra, e);
        }
        return "redirect:/admin/photos/" + id;
    }

    @PostMapping("/admin/photos/{id:\\d+}/{photoId}/delete")
    public String deletePhoto(@PathVariable Long id, @PathVariable Long photoId, RedirectAttributes ra) {
        try {
            photoService.delete(id, photoId);
            ok(ra, "Фото удалено");
        } catch (RuntimeException e) {
            fail(ra, e);
        }
        return "redirect:/admin/photos/" + id;
    }

    /** Сдвинуть фото: dir = up / down / first (сделать обложкой). */
    @PostMapping("/admin/photos/{id:\\d+}/{photoId}/move")
    public String move(@PathVariable Long id, @PathVariable Long photoId, @RequestParam String dir, RedirectAttributes ra) {
        try {
            Product p = productService.findById(id);
            List<ProductPhoto> album = new ArrayList<>(p.getPhotos());
            album.sort(Comparator.comparingInt(ProductPhoto::getPosition));
            List<Long> ids = new ArrayList<>(album.stream().map(ProductPhoto::getId).toList());
            int i = ids.indexOf(photoId);
            if (i < 0) throw new IllegalArgumentException("Фото не найдено");
            ids.remove(i);
            int to = switch (dir) {
                case "up" -> Math.max(0, i - 1);
                case "down" -> Math.min(ids.size(), i + 1);
                default -> 0;
            };
            ids.add(to, photoId);
            photoService.reorder(id, ids);
            ok(ra, to == 0 ? "Фото стало обложкой" : "Порядок фото изменён");
        } catch (RuntimeException e) {
            fail(ra, e);
        }
        return "redirect:/admin/photos/" + id + "?sel=" + photoId;
    }
}
