package com.confectionery.crm.admin;

import com.confectionery.crm.domain.*;
import com.confectionery.crm.repository.PurchaseRepository;
import com.confectionery.crm.repository.StockMovementRepository;
import com.confectionery.crm.repository.UserRepository;
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
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;

import static com.confectionery.crm.admin.AdminSupport.*;

/**
 * Покупки сырья у поставщиков по макету (часть 2): плитки (за 30 дней, не ушли в 1С, пора купить), список с
 * поиском, периодом, поставщиком и отборами, карточка (цена за единицу против прошлой покупки, поставщик, фото
 * накладной, 1С, что сейчас на складе), новая и изменение (PurchaseService — приход на склад сырья и
 * «Поступление товаров» в 1С), приходная накладная в PDF, поставщики (контрагенты 1С).
 */
@Controller
@RequestMapping("/admin/purchases")
@RequiredArgsConstructor
public class AdminPurchaseController {

    private final PurchaseService purchaseService;
    private final PurchaseRepository purchaseRepository;
    private final CounterpartyService counterpartyService;
    private final IngredientService ingredientService;
    private final ProductionRequestService requestService;
    private final StockMovementRepository movementRepository;
    private final InvoicePdfService invoicePdfService;
    private final CurrentUserService currentUserService;
    private final UserRepository userRepository;

    static final AdminFormat F = new AdminFormat();

    Function<String, String> who() {
        Map<String, String> cache = new HashMap<>();
        return u -> {
            if (u == null || u.isBlank()) return "—";
            return cache.computeIfAbsent(u, k -> userRepository.findByUsername(k)
                    .map(x -> x.getFullName() != null && !x.getFullName().isBlank() ? x.getFullName() : x.getUsername()).orElse(k));
        };
    }

    private boolean canEdit() {
        return currentUserService.hasRole(Role.ADMIN, Role.WAREHOUSE_RAW);
    }

    private boolean canSync() {
        return currentUserService.hasRole(Role.ADMIN, Role.ACCOUNTANT);
    }

    public record Chip(String key, String label, int count, boolean on, String href, boolean alert) {
    }

    public record Opt(String value, String label, boolean on) {
    }

    public record Tile(String href, String cls, String icon, String label, String value, String unit, String sub) {
    }

    // ================================================================== список

    static final List<String[]> PERS = List.of(new String[]{"7", "Период: 7 дней"}, new String[]{"30", "Период: 30 дней"},
            new String[]{"90", "Период: 90 дней"}, new String[]{"all", "Период: всё"});

    @GetMapping
    @Transactional(readOnly = true)
    public String list(@RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "30") String per,
                       @RequestParam(defaultValue = "all") String sup, @RequestParam(defaultValue = "all") String seg, Model model) {
        List<Purchase> all = purchaseService.findAll();
        LocalDate today = LocalDate.now();
        LocalDate from = per.equals("all") ? LocalDate.MIN : today.minusDays(Long.parseLong(per.matches("\\d+") ? per : "30") - 1);
        Long supId = AdminSupport.id(sup);
        String[] words = AdminRequests.norm(q).isEmpty() ? new String[0] : AdminRequests.norm(q).split("\\s+");
        List<Purchase> base = all.stream().filter(p -> !p.getPurchaseDate().isBefore(from))
                .filter(p -> supId == null || (p.getCounterparty() != null && supId.equals(p.getCounterparty().getId())))
                .filter(p -> {
                    String hay = AdminRequests.norm(p.getId() + " " + (p.getCounterparty() == null ? "" : p.getCounterparty().getName()) + " "
                            + String.join(" ", p.getItems().stream().map(i -> i.getIngredient().getName()).toList()));
                    for (String w : words) if (!hay.contains(w.replaceFirst("^№", ""))) return false;
                    return true;
                }).toList();
        Map<String, java.util.function.Predicate<Purchase>> segs = new LinkedHashMap<>();
        segs.put("all", p -> true);
        segs.put("err", p -> p.getOneCSyncError() != null);
        segs.put("nophoto", p -> p.getImageUrl() == null || p.getImageUrl().isBlank());
        Map<String, String> labels = Map.of("all", "Все", "err", "Не ушли в 1С", "nophoto", "Без фото накладной");
        String qs = "&per=" + per + "&sup=" + sup + (q.isBlank() ? "" : "&q=" + java.net.URLEncoder.encode(q, StandardCharsets.UTF_8));
        List<Chip> chips = new ArrayList<>();
        segs.forEach((k, f) -> {
            int n = (int) base.stream().filter(f).count();
            chips.add(new Chip(k, labels.get(k), n, seg.equals(k), "/admin/purchases?seg=" + k + qs, !k.equals("all") && n > 0));
        });
        List<Purchase> rows = new ArrayList<>(base.stream().filter(segs.getOrDefault(seg, segs.get("all"))).toList());
        rows.sort((a, b) -> AdminPurchases.before(a, b) ? 1 : AdminPurchases.before(b, a) ? -1 : 0);
        List<Counterparty> cps = new ArrayList<>(counterpartyService.findAll());
        cps.sort(Comparator.comparing(Counterparty::getName, String.CASE_INSENSITIVE_ORDER));
        model.addAttribute("tiles", tiles(all));
        model.addAttribute("rows", rows.stream().map(AdminPurchases::row).toList());
        model.addAttribute("sum", rows.stream().map(Purchase::getTotalSum).reduce(BigDecimal.ZERO, BigDecimal::add));
        model.addAttribute("chips", chips);
        model.addAttribute("pers", PERS.stream().map(p -> new Opt(p[0], p[1], p[0].equals(per))).toList());
        model.addAttribute("sups", cps.stream().map(c -> new Opt(String.valueOf(c.getId()), c.getName(), String.valueOf(c.getId()).equals(sup))).toList());
        model.addAttribute("q", q);
        model.addAttribute("per", per);
        model.addAttribute("sup", sup);
        model.addAttribute("seg", seg);
        model.addAttribute("canEdit", canEdit());
        page(model, "Покупки", "сырьё от поставщиков", "purchases");
        return "admin/purchases/list";
    }

    private List<Tile> tiles(List<Purchase> all) {
        LocalDate from = LocalDate.now().minusDays(29);
        List<Purchase> last = all.stream().filter(p -> !p.getPurchaseDate().isBefore(from)).toList();
        BigDecimal sum = last.stream().map(Purchase::getTotalSum).reduce(BigDecimal.ZERO, BigDecimal::add);
        Set<Long> sups = new HashSet<>();
        Map<String, BigDecimal> byM = new HashMap<>();
        last.forEach(p -> {
            if (p.getCounterparty() != null) sups.add(p.getCounterparty().getId());
            p.getItems().forEach(i -> { if (i.getTotalPrice() != null) byM.merge(i.getIngredient().getName(), i.getTotalPrice(), BigDecimal::add); });
        });
        Map.Entry<String, BigDecimal> top = byM.entrySet().stream().max(Map.Entry.comparingByValue()).orElse(null);
        String s1 = F.count(last.size(), "покупка", "покупки", "покупок") + " · " + F.count(sups.size(), "поставщик", "поставщика", "поставщиков")
                + (top != null ? " · больше всего — " + top.getKey() + ", " + F.money(top.getValue()) : "");
        List<Purchase> err = all.stream().filter(p -> p.getOneCSyncError() != null).sorted(Comparator.comparing(Purchase::getId)).toList();
        boolean unlinked = err.stream().allMatch(p -> p.getCounterparty() != null && p.getCounterparty().getOneCContragentKey() == null);
        String s2 = err.isEmpty() ? "все покупки в 1С" : (unlinked ? "поставщик не связан с 1С: " : "не ушли: ")
                + String.join(", ", err.stream().map(p -> "№ " + p.getId()).toList());
        Map<Long, AdminRequests.Need> need = AdminRequests.needNew(requestService.findAll());
        List<AdminMaterials.State> st = new ArrayList<>();
        List<String> buy = new ArrayList<>();
        boolean anyShort = false;
        List<Ingredient> act = ingredientService.findAllActive();
        List<Ingredient> sorted = new ArrayList<>(act);
        sorted.sort(Comparator.comparing(i -> AdminMaterials.rank(AdminMaterials.state(i, need.get(i.getId())))));
        for (Ingredient i : sorted) {
            AdminMaterials.State s = AdminMaterials.state(i, need.get(i.getId()));
            if (s.key().equals("short") || s.key().equals("low") || s.key().equals("zero")) {
                st.add(s);
                buy.add(i.getName() + (s.key().equals("short") ? " — не хватит на заявки" : ""));
                anyShort |= s.key().equals("short");
            }
        }
        String s3 = buy.isEmpty() ? "всего хватает" : String.join(" · ", buy.subList(0, Math.min(4, buy.size()))) + (buy.size() > 4 ? " и ещё " + (buy.size() - 4) : "");
        return List.of(
                new Tile(null, "", "purchases", "За 30 дней", F.dec(sum, 0), "₸", s1),
                new Tile("/admin/purchases?seg=err&per=all", err.isEmpty() ? "" : "kpi--alert", "alert", "Не ушли в 1С", String.valueOf(err.size()), null, s2),
                new Tile("/admin/materials?seg=" + (anyShort ? "short" : "low"), buy.isEmpty() ? "" : "kpi--warm", "materials", "Пора купить", String.valueOf(buy.size()), null, s3));
    }

    // ================================================================== карточка

    public record Stock(Ingredient i, BigDecimal stock) {
    }

    @GetMapping("/{id:\\d+}")
    @Transactional(readOnly = true)
    public String view(@PathVariable Long id, Model model) {
        Purchase p;
        try {
            p = purchaseService.findById(id);
        } catch (IllegalArgumentException e) {
            model.addAttribute("missing", id);
            page(model, "Покупки", "", "purchases");
            return "admin/purchases/view";
        }
        List<Long> ids = p.getItems().stream().map(i -> i.getIngredient().getId()).distinct().toList();
        List<PurchaseItem> priced = ids.isEmpty() ? List.of() : purchaseRepository.findPricedItemsNewestFirst(ids);
        List<Purchase> ofSup = p.getCounterparty() == null ? List.of() : purchaseService.findAll().stream()
                .filter(x -> x.getCounterparty() != null && x.getCounterparty().getId().equals(p.getCounterparty().getId())).toList();
        model.addAttribute("p", p);
        model.addAttribute("oneC", AdminPurchases.oneC(p));
        model.addAttribute("lines", AdminPurchases.lines(p, priced));
        model.addAttribute("by", who().apply(p.getCreatedBy()));
        model.addAttribute("what", AdminPurchases.what(ofSup));
        model.addAttribute("stock", p.getItems().stream().map(i -> new Stock(i.getIngredient(), i.getIngredient().getQuantityInStock())).toList());
        model.addAttribute("canEdit", canEdit());
        model.addAttribute("canSync", canSync());
        page(model, "Покупки", "", "purchases");
        return "admin/purchases/view";
    }

    /** Приходная накладная по покупке в PDF — как в приложении (InvoicePdfService.generatePurchase). */
    @GetMapping("/{id:\\d+}/invoice.pdf")
    public ResponseEntity<byte[]> invoice(@PathVariable Long id) {
        Purchase p = purchaseService.findById(id);
        byte[] pdf = invoicePdfService.generatePurchase(p);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename("Приходная_накладная_" + p.getId() + "_" + p.getPurchaseDate() + ".pdf", StandardCharsets.UTF_8).build().toString())
                .body(pdf);
    }

    // ================================================================== новая / изменить

    @GetMapping("/new")
    @Transactional(readOnly = true)
    public String newForm(@RequestParam(required = false) Long ingredient, Model model, RedirectAttributes ra) {
        if (!canEdit()) {
            fail(ra, new IllegalStateException("Покупки оформляют склад сырья и администратор"));
            return "redirect:/admin/purchases";
        }
        return form(null, ingredient, List.of(), null, model);
    }

    @GetMapping("/{id:\\d+}/edit")
    @Transactional(readOnly = true)
    public String editForm(@PathVariable Long id, Model model, RedirectAttributes ra) {
        if (!canEdit()) {
            fail(ra, new IllegalStateException("Покупки меняют склад сырья и администратор"));
            return "redirect:/admin/purchases/" + id;
        }
        return form(purchaseService.findById(id), null, List.of(), null, model);
    }

    /**
     * «Купить» со склада или из заявки: поставщик — последний, количество — до минимума и на новые заявки
     * (с запасом на четыре дня расхода); если сырья и так хватает — как в прошлой покупке.
     */
    private Map<String, Object> preset(Long ingId, Map<Long, AdminMaterials.Price> prices, Map<Long, Long> lastSup, Map<Long, BigDecimal> lastQty) {
        Ingredient m;
        try {
            m = ingredientService.findById(ingId);
        } catch (RuntimeException e) {
            return null;
        }
        LocalDate today = LocalDate.now();
        AdminRequests.Need n = AdminRequests.needNew(requestService.findAll()).get(ingId);
        Map<Long, BigDecimal> use = AdminMaterials.usage(movementRepository.findByIngredientIdAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(ingId,
                today.minusDays(AdminMaterials.USE_DAYS).atStartOfDay()), today);
        double need = n == null ? 0 : n.q().doubleValue(), min = AdminRequests.nz(m.getMinThreshold()).doubleValue(),
                u = use.getOrDefault(ingId, BigDecimal.ZERO).doubleValue(), stock = AdminRequests.nz(m.getQuantityInStock()).doubleValue();
        double want = Math.max(Math.max(need + min, min * 1.5), u * 4) - stock;
        double step = want >= 150 ? 50 : want >= 30 ? 10 : want >= 8 ? 5 : 1;
        double q = want <= 0 && lastQty.get(ingId) != null ? lastQty.get(ingId).doubleValue() : Math.max(step, Math.ceil(want / step) * step);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("m", ingId);
        r.put("q", BigDecimal.valueOf(q).stripTrailingZeros());
        AdminMaterials.Price pr = prices.get(ingId);
        r.put("s", pr == null ? null : pr.unit().multiply(BigDecimal.valueOf(q)).setScale(0, RoundingMode.HALF_UP));
        r.put("sup", lastSup.get(ingId));
        return r;
    }

    private String form(Purchase p, Long presetIng, List<String> errors, Map<String, Object> posted, Model model) {
        List<Counterparty> cps = new ArrayList<>(counterpartyService.findAll());
        cps.sort(Comparator.comparing(Counterparty::getName, String.CASE_INSENSITIVE_ORDER));
        List<Ingredient> act = ingredientService.findAllActive();
        List<Long> ids = act.stream().map(Ingredient::getId).toList();
        List<PurchaseItem> priced = ids.isEmpty() ? List.of() : purchaseRepository.findPricedItemsNewestFirst(ids);
        Map<Long, AdminMaterials.Price> prices = AdminMaterials.prices(priced);
        Map<Long, Long> lastSup = new HashMap<>();
        Map<Long, BigDecimal> lastQty = new HashMap<>();
        for (PurchaseItem it : priced) {
            if (it.getPurchase() != null && it.getPurchase().getCounterparty() != null) lastSup.putIfAbsent(it.getIngredient().getId(), it.getPurchase().getCounterparty().getId());
            lastQty.putIfAbsent(it.getIngredient().getId(), it.getQuantity());
        }
        Map<String, Object> data = AdminPurchases.formData(p, cps, act, prices, lastSup, presetIng == null ? null : preset(presetIng, prices, lastSup, lastQty));
        if (posted != null) data.putAll(posted);
        data.put("errors", errors);
        model.addAttribute("p", p);
        model.addAttribute("formJson", AdminJson.of(data));
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
        List<PurchaseService.ItemInput> items = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        boolean bad = false;
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < is.length; i++) {
            Long ing = AdminSupport.id(is[i]);
            BigDecimal q = num(at(qs, i)), t = num(at(ts, i));
            if (ing == null && q == null && t == null) continue;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("m", ing);
            r.put("q", q);
            r.put("s", t);
            rows.add(r);
            if (ing == null || q == null || q.signum() <= 0 || (t != null && t.signum() < 0)) {
                bad = true;
                continue;
            }
            if (!seen.add(ing)) errors.add("Сырьё дважды — сложите в одну строку.");
            items.add(new PurchaseService.ItemInput(ing, q, t));
        }
        if (!canEdit()) errors.add("Покупки оформляют склад сырья и администратор.");
        if (cp == null && newCp.isEmpty()) errors.add("Выберите поставщика.");
        if (date.isAfter(LocalDate.now())) errors.add("Дата покупки — не в будущем.");
        if (bad) errors.add("Проверьте строки: сырьё и количество больше нуля.");
        else if (items.isEmpty()) errors.add("Добавьте сырьё.");
        if (errors.isEmpty()) {
            try {
                if (cp == null) cp = counterpartyService.create(newCp).getId();
                String by = currentUserService.username().orElse("admin");
                Purchase p = id == null ? purchaseService.create(cp, items, date, by) : purchaseService.update(id, cp, items, date, by);
                ok(ra, "Покупка № " + p.getId() + (id == null ? " оформлена" : " сохранена") + " — сырьё на складе: " + String.join(", ",
                        p.getItems().stream().map(it -> it.getIngredient().getName() + " +" + AdminRequests.qu(it.getQuantity(), AdminRequests.unit(it.getIngredient()))).toList()));
                return "redirect:/admin/purchases/" + p.getId();
            } catch (RuntimeException e) {
                errors.add(e.getMessage());
            }
        }
        Map<String, Object> posted = new LinkedHashMap<>();
        posted.put("rows", rows);
        posted.put("sup", cp);
        posted.put("newSup", newCp.isEmpty() ? null : newCp);
        posted.put("date", date.toString());
        return form(id == null ? null : purchaseService.findById(id), null, errors, posted, model);
    }

    @PostMapping("/{id:\\d+}/image")
    public String image(@PathVariable Long id, @RequestParam("file") MultipartFile file, RedirectAttributes ra) {
        try {
            if (!canEdit()) throw new IllegalStateException("Фото накладной прикрепляют склад сырья и администратор");
            purchaseService.attachImage(id, file);
            ok(ra, "Фото накладной прикреплено к покупке № " + id);
        } catch (RuntimeException e) {
            fail(ra, e);
        }
        return "redirect:/admin/purchases/" + id;
    }

    @PostMapping("/{id:\\d+}/sync-1c")
    public String resync(@PathVariable Long id, RedirectAttributes ra) {
        try {
            if (!canSync()) throw new IllegalStateException("Отправить в 1С могут бухгалтер и администратор");
            purchaseService.resyncOneC(id);
            ok(ra, "Покупка № " + id + " отправлена в 1С ещё раз");
        } catch (RuntimeException e) {
            fail(ra, e);
        }
        return "redirect:/admin/purchases/" + id;
    }

    // ================================================================== поставщики

    @GetMapping("/suppliers")
    @Transactional(readOnly = true)
    public String suppliers(@RequestParam(defaultValue = "") String q, Model model) {
        List<Counterparty> cps = new ArrayList<>(counterpartyService.findAll());
        cps.sort(Comparator.comparing(Counterparty::getName, String.CASE_INSENSITIVE_ORDER));
        List<AdminPurchases.Supplier> rows = AdminPurchases.suppliers(cps, purchaseService.findAll());
        String[] words = AdminRequests.norm(q).isEmpty() ? new String[0] : AdminRequests.norm(q).split("\\s+");
        rows = rows.stream().filter(s -> {
            String hay = AdminRequests.norm(s.c().getName() + " " + s.what());
            for (String w : words) if (!hay.contains(w)) return false;
            return true;
        }).toList();
        model.addAttribute("rows", rows);
        model.addAttribute("q", q);
        model.addAttribute("canEdit", canEdit());
        model.addAttribute("isAdmin", currentUserService.hasRole(Role.ADMIN));
        model.addAttribute("namesJson", AdminJson.of(cps.stream().map(Counterparty::getName).toList()));
        page(model, "Поставщики", "кто что возит", "purchases");
        return "admin/purchases/suppliers";
    }

    @PostMapping("/suppliers")
    public String addSupplier(@RequestParam String name, @RequestParam(required = false) String back, RedirectAttributes ra) {
        try {
            if (!canEdit()) throw new IllegalStateException("Поставщиков добавляют склад сырья и администратор");
            Counterparty c = counterpartyService.create(name);
            ok(ra, "Поставщик «" + c.getName() + "» добавлен" + (c.getOneCContragentKey() != null ? " — и в 1С тоже" : c.getOneCSyncError() != null ? ", но в 1С не создан: " + c.getOneCSyncError() : ""));
        } catch (RuntimeException e) {
            fail(ra, e);
        }
        return "redirect:/admin/purchases/suppliers";
    }

    @PostMapping("/suppliers/{id}/delete")
    public String deleteSupplier(@PathVariable Long id, RedirectAttributes ra) {
        try {
            if (!canEdit()) throw new IllegalStateException("Поставщиков удаляют склад сырья и администратор");
            counterpartyService.delete(id);
            ok(ra, "Поставщик удалён");
        } catch (RuntimeException e) {
            fail(ra, e);
        }
        return "redirect:/admin/purchases/suppliers";
    }

    @PostMapping("/suppliers/sync-1c")
    public String syncSuppliers(@RequestParam(required = false) String next, RedirectAttributes ra) {
        try {
            if (!currentUserService.hasRole(Role.ADMIN)) throw new IllegalStateException("Загрузить поставщиков из 1С может администратор");
            CounterpartyService.SyncResult r = counterpartyService.syncFromOneC();
            ok(ra, "Из 1С: добавлено " + r.created() + ", обновлено " + r.updated() + ", удалено " + r.deleted()
                    + (r.skippedHasPurchases().isEmpty() ? "" : "; в 1С их нет, но есть покупки — оставлены: " + String.join(", ", r.skippedHasPurchases())));
        } catch (RuntimeException e) {
            fail(ra, e);
        }
        return AdminSupport.back(next, "/admin/purchases/suppliers");
    }
}
