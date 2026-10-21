package com.confectionery.crm.admin;

import com.confectionery.crm.domain.*;
import com.confectionery.crm.repository.OrderRepository;
import com.confectionery.crm.repository.ProductRepository;
import com.confectionery.crm.repository.StockMovementRepository;
import com.confectionery.crm.repository.UserRepository;
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
 * Склад готовой продукции по макету (часть 3, /admin/stock): плитки «Не хватит на заказы», «Из цеха сегодня»,
 * «Выдано сегодня», «На складе»; остатки — резерв под открытые заказы, свободно, что в производстве, сегодня,
 * последний выпуск, «Заявка на производство», если не хватает; движения по дням — из цеха (оприходование заявок),
 * выдача заказов, документы «Поступление»/«Списание» (StockService — проводятся и уходят в 1С, как из приложения),
 * отмена оприходования; строку ручного документа удаляет администратор. Склад сырья — AdminMaterialController.
 */
@Controller
@RequestMapping("/admin/stock")
@RequiredArgsConstructor
public class AdminWarehouseController {

    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;
    private final StockMovementRepository movementRepository;
    private final ProductionRequestService requestService;
    private final StockService stockService;
    private final OneCStockSyncService oneCStockSyncService;
    private final CurrentUserService currentUserService;
    private final UserRepository userRepository;

    static final AdminFormat F = new AdminFormat();
    static final String NB = AdminFormat.NB;
    /** Сколько дней журнала смотреть для «Выпуск» (последнее оприходование). */
    static final int LAST_DAYS = 30;

    public record Chip(String key, String label, int count, boolean on, String href, boolean alert) {
    }

    public record Opt(String value, String label, boolean on) {
    }

    public record Tile(String href, String cls, String icon, String label, String value, String unit, String sub) {
    }

    private boolean can(Role... roles) {
        return currentUserService.hasRole(roles);
    }

    /** Документы склада ГП — как в приложении (StockMovementApiController): склад ГП, бухгалтер, администратор. */
    private boolean canDoc() {
        return can(Role.ADMIN, Role.WAREHOUSE_FG, Role.ACCOUNTANT);
    }

    Function<String, String> who() {
        return AdminStock.who(userRepository);
    }

    static final List<String[]> SORTS = List.of(new String[]{"att", "Сначала важные"}, new String[]{"qd", "Остаток: больше сверху"},
            new String[]{"qa", "Остаток: меньше сверху"}, new String[]{"name", "По названию"});
    static final List<String[]> PERS = List.of(new String[]{"today", "Период: сегодня"}, new String[]{"yest", "Период: вчера"},
            new String[]{"7", "Период: 7 дней"}, new String[]{"14", "Период: 14 дней"}, new String[]{"30", "Период: 30 дней"});
    static final List<String[]> TYPES = List.of(new String[]{"all", "Все"}, new String[]{"prod", "Из цеха"}, new String[]{"ship", "Выдача заказов"},
            new String[]{"in", "Поступление"}, new String[]{"off", "Списание"});

    /** Движения товара за период: журнал склада и строки заказов, отгруженных через CRM. Новые сверху. */
    private List<AdminStock.Move> moves(LocalDate from, LocalDate to) {
        Function<String, String> who = who();
        LocalDateTime a = from.atStartOfDay(), b = to.plusDays(1).atStartOfDay();
        List<AdminStock.Move> out = new ArrayList<>();
        for (StockMovement x : movementRepository.findByProductIsNotNullAndCreatedAtBetweenOrderByCreatedAtDesc(a, b)) {
            if (x.getProduct().isSemiFinished()) continue;                 // полуфабрикат — на складе сырья
            out.add(AdminStock.move(x, who));
        }
        for (Order o : orderRepository.findByShippedAtBetween(a, b)) {
            if (AdminStock.movesStock(o)) out.addAll(AdminStock.shipped(o, who));
        }
        out.removeIf(m -> m.p().isSemiFinished());
        out.sort(Comparator.comparing(AdminStock.Move::at).reversed());
        return out;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public String list(@RequestParam(defaultValue = "stock") String tab, @RequestParam(defaultValue = "") String q,
                       @RequestParam(defaultValue = "att") String sort, @RequestParam(defaultValue = "all") String seg,
                       @RequestParam(defaultValue = "7") String per, @RequestParam(defaultValue = "all") String t,
                       @RequestParam(defaultValue = "60") int limit, Model model) {
        LocalDate today = LocalDate.now();
        List<AdminStock.Move> recent = moves(today.minusDays(LAST_DAYS - 1), today);
        Map<Long, AdminStock.Last> last = new HashMap<>();
        for (AdminStock.Move m : recent) {
            if (m.kind().key().equals("prod") && !last.containsKey(m.p().getId())) {
                java.util.regex.Matcher mt = java.util.regex.Pattern.compile("№\\s*(\\d+)").matcher(m.doc());
                last.put(m.p().getId(), new AdminStock.Last(m.at().toLocalDate(), mt.find() ? mt.group(1) : null));
            }
        }
        List<AdminStock.Move> todayMoves = recent.stream().filter(m -> m.at().toLocalDate().equals(today)).toList();
        List<ProductionRequest> reqs = requestService.findAll();
        Map<Long, Recipe> recipes = new HashMap<>();
        requestService.recipesWithProducts().forEach(rc -> recipes.put(rc.getProduct().getId(), rc));
        List<AdminStock.Row> rows = AdminStock.rows(productRepository.findAll(), AdminStock.reserve(orderRepository.findByStatusInOrderByCreatedAtAsc(AdminStock.OPEN), today),
                AdminStock.inWork(reqs), todayMoves, last, recipes);
        model.addAttribute("tiles", tiles(rows, todayMoves));
        model.addAttribute("tab", tab.equals("moves") ? "moves" : "stock");
        model.addAttribute("canIn", canDoc());
        model.addAttribute("canOff", canDoc());
        model.addAttribute("canSync", can(Role.ADMIN));
        model.addAttribute("canDel", can(Role.ADMIN));
        model.addAttribute("canMake", can(Role.ADMIN, Role.ACCOUNTANT, Role.TECHNOLOGIST));
        model.addAttribute("docJson", AdminJson.of(AdminStock.docData(rows, canDoc(), canDoc())));
        model.addAttribute("lastFrom", today.minusDays(LAST_DAYS - 1));
        if (tab.equals("moves")) movesTab(per, t, q, limit, model);
        else stockTab(rows, q, sort, seg, model);
        page(model, "Склад", "готовая продукция: остатки, заказы, движения", "stock");
        return "admin/warehouse/stock";
    }

    private void stockTab(List<AdminStock.Row> rows, String q, String sort, String seg, Model model) {
        String[] words = AdminRequests.norm(q).isEmpty() ? new String[0] : AdminRequests.norm(q).split("\\s+");
        List<AdminStock.Row> base = rows.stream().filter(r -> {
            String hay = AdminRequests.norm(r.p().getName() + " " + (r.p().getOneCCode() == null ? "" : r.p().getOneCCode()));
            for (String w : words) if (!hay.contains(w)) return false;
            return true;
        }).toList();
        Map<String, java.util.function.Predicate<AdminStock.Row>> segs = new LinkedHashMap<>();
        segs.put("all", r -> true);
        segs.put("short", r -> r.state().equals("short"));
        segs.put("res", r -> r.rq().signum() > 0);
        segs.put("zero", r -> r.stock().signum() <= 0);
        Map<String, String> labels = Map.of("all", "Все", "short", "Не хватит на заказы", "res", "В заказах", "zero", "Нет на складе");
        String qs = (q.isBlank() ? "" : "&q=" + java.net.URLEncoder.encode(q, StandardCharsets.UTF_8)) + "&sort=" + sort;
        List<Chip> chips = new ArrayList<>();
        segs.forEach((k, f) -> {
            int n = (int) base.stream().filter(f).count();
            chips.add(new Chip(k, labels.get(k), n, seg.equals(k), "/admin/stock?seg=" + k + qs, k.equals("short") && n > 0));
        });
        List<AdminStock.Row> list = new ArrayList<>(base.stream().filter(segs.getOrDefault(seg, segs.get("all"))).toList());
        AdminStock.sort(list, sort);
        model.addAttribute("rows", list);
        model.addAttribute("chips", chips);
        model.addAttribute("sorts", SORTS.stream().map(s -> new Opt(s[0], s[1], s[0].equals(sort))).toList());
        model.addAttribute("q", q);
        model.addAttribute("seg", seg);
        model.addAttribute("sort", sort);
    }

    private void movesTab(String per, String t, String q, int limit, Model model) {
        LocalDate today = LocalDate.now();
        LocalDate from = switch (per) {
            case "today" -> today;
            case "yest" -> today.minusDays(1);
            case "14" -> today.minusDays(13);
            case "30" -> today.minusDays(29);
            default -> today.minusDays(6);
        };
        LocalDate to = per.equals("yest") ? from : today;
        String[] words = AdminRequests.norm(q).isEmpty() ? new String[0] : AdminRequests.norm(q).split("\\s+");
        List<AdminStock.Move> base = moves(from, to).stream().filter(m -> AdminStock.matches(m, words)).toList();
        Map<String, Integer> cnt = new HashMap<>();
        base.forEach(m -> cnt.merge(m.kind().key().equals("unprod") ? "off" : m.kind().key(), 1, Integer::sum));
        String qs = "&per=" + per + (q.isBlank() ? "" : "&q=" + java.net.URLEncoder.encode(q, StandardCharsets.UTF_8));
        List<Chip> chips = TYPES.stream().map(x -> new Chip(x[0], x[1], x[0].equals("all") ? base.size() : cnt.getOrDefault(x[0], 0), t.equals(x[0]),
                "/admin/stock?tab=moves&t=" + x[0] + qs, false)).toList();
        List<AdminStock.Move> list = base.stream().filter(m -> t.equals("all") || m.kind().key().equals(t) || (t.equals("off") && m.kind().key().equals("unprod"))).toList();
        BigDecimal in = BigDecimal.ZERO, out = BigDecimal.ZERO;
        for (AdminStock.Move m : list) {
            if (m.plus()) in = in.add(m.q());
            else out = out.add(m.q());
        }
        List<AdminStock.Day> days = AdminStock.days(list);
        int groups = days.stream().mapToInt(d -> d.groups().size()).sum();
        int lim = Math.max(60, limit);
        List<AdminStock.Day> shown = new ArrayList<>();
        int left = lim;
        for (AdminStock.Day d : days) {
            if (left <= 0) break;
            List<AdminStock.Group> gs = d.groups().size() <= left ? d.groups() : d.groups().subList(0, left);
            shown.add(new AdminStock.Day(d.day(), d.title(), d.count(), gs));
            left -= gs.size();
        }
        model.addAttribute("days", shown);
        model.addAttribute("rest", Math.max(0, groups - lim));
        model.addAttribute("moreHref", "/admin/stock?tab=moves&t=" + t + qs + "&limit=" + (lim + 60));
        model.addAttribute("typeChips", chips);
        model.addAttribute("pers", PERS.stream().map(p -> new Opt(p[0], p[1], p[0].equals(per))).toList());
        model.addAttribute("mq", q);
        model.addAttribute("per", per);
        model.addAttribute("t", t);
        model.addAttribute("moveCount", list.size());
        model.addAttribute("inQ", in);
        model.addAttribute("outQ", out);
        model.addAttribute("back", "/admin/stock?tab=moves&t=" + t + qs);
    }

    /** «8,4 млн» / «640 тыс» — для крупной цифры плитки. */
    static String shortMoney(BigDecimal v) {
        if (v.compareTo(BigDecimal.valueOf(1_000_000)) >= 0) return F.dec(v.divide(BigDecimal.valueOf(1_000_000), 1, RoundingMode.HALF_UP), 1) + NB + "млн";
        if (v.compareTo(BigDecimal.valueOf(1_000)) >= 0) return F.dec(v.divide(BigDecimal.valueOf(1_000), 0, RoundingMode.HALF_UP), 0) + NB + "тыс";
        return F.dec(v, 0);
    }

    static List<Tile> tiles(List<AdminStock.Row> rows, List<AdminStock.Move> today) {
        List<AdminStock.Row> sh = rows.stream().filter(r -> r.state().equals("short") && r.p().isActive())
                .sorted(Comparator.comparing(AdminStock.Row::free)).toList();
        List<AdminStock.Row> cov = sh.stream().filter(AdminStock.Row::covered).toList();
        boolean need = sh.size() > cov.size();
        String s1 = sh.isEmpty() ? "остатка хватает на все открытые заказы"
                : String.join(" · ", sh.stream().map(r -> AdminRequests.shortName(r.p().getName()) + " −" + F.num(r.free().negate())).toList())
                + (cov.isEmpty() ? "" : " · в работе: " + String.join("; ", cov.stream().map(r -> r.work().nos()).distinct().toList()));
        BigDecimal in = BigDecimal.ZERO, out = BigDecimal.ZERO;
        Set<String> reqs = new LinkedHashSet<>(), ords = new LinkedHashSet<>();
        for (AdminStock.Move m : today) {
            String no = m.doc().replaceAll("^.*№\\s*(\\d+).*$", "$1");
            if (m.kind().key().equals("prod")) {
                in = in.add(m.q());
                if (no.matches("\\d+")) reqs.add(no);
            } else if (m.kind().key().equals("ship")) {
                out = out.add(m.q());
                if (no.matches("\\d+")) ords.add(no);
            }
        }
        BigDecimal value = BigDecimal.ZERO;
        int pos = 0;
        for (AdminStock.Row r : rows) {
            if (r.value() != null) {
                value = value.add(r.value());
                pos++;
            }
        }
        return List.of(
                new Tile("/admin/stock?seg=short", need ? "kpi--alert" : sh.isEmpty() ? "" : "kpi--warm", "alert", "Не хватит на заказы", String.valueOf(sh.size()), null, s1),
                new Tile("/admin/stock?tab=moves&per=today&t=prod", "", "production", "Из цеха сегодня", F.num(in), "шт",
                        reqs.isEmpty() ? "сегодня ещё не оприходовали" : "оприходовано по " + (reqs.size() > 1 ? "заявкам" : "заявке") + " № " + String.join(", ", reqs)),
                new Tile("/admin/stock?tab=moves&per=today&t=ship", "", "truck", "Выдано сегодня", F.num(out), "шт",
                        ords.isEmpty() ? "сегодня ещё не выдавали" : "заказы № " + String.join(", ", ords)),
                new Tile(null, "", "stock", "На складе", shortMoney(value), "₸",
                        F.count(pos, "позиция", "позиции", "позиций") + " · по ценам прайса · " + F.money(value)));
    }

    // ================================================================== действия

    @PostMapping("/doc")
    public String saveDoc(HttpServletRequest req, RedirectAttributes ra) {
        boolean off = "out".equals(req.getParameter("type")) || "off".equals(req.getParameter("type"));
        // назад — на склад или в карточку товара («Списать со склада»)
        String back = Optional.ofNullable(req.getParameter("back")).filter(s -> s.startsWith("/admin/stock") || s.matches("/admin/products/\\d+"))
                .orElse("/admin/stock?tab=moves");
        try {
            if (!canDoc()) throw new IllegalStateException("Документы склада проводят склад готовой продукции, бухгалтер и администратор");
            LocalDate date = date(req.getParameter("date"), LocalDate.now());
            String reason = Optional.ofNullable(req.getParameter("reason")).orElse("").trim();
            String[] ids = arr(req, "item"), qs = arr(req, "qty");
            List<StockService.DocumentLine> doc = new ArrayList<>();
            Set<Long> seen = new HashSet<>();
            for (int i = 0; i < ids.length; i++) {
                Long id = AdminSupport.id(ids[i]);
                BigDecimal q = num(at(qs, i));
                if (id == null && q == null) continue;
                if (id == null || q == null || q.signum() <= 0) throw new IllegalArgumentException("Проверьте строки: товар и количество больше нуля");
                if (!seen.add(id)) throw new IllegalArgumentException("Товар дважды в документе — сложите в одну строку");
                Product p = productRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Товар не найден"));
                if (off && q.compareTo(AdminStock.nz(p.getQuantityInStock())) > 0) {
                    throw new IllegalArgumentException(p.getName() + ": на складе только " + F.num(AdminStock.nz(p.getQuantityInStock())) + NB + "шт — документ не проведён");
                }
                doc.add(new StockService.DocumentLine(null, p, q));
            }
            StockDocument d = stockService.createDocument(off ? StockMovementType.WRITE_OFF : StockMovementType.RECEIPT, true, date, reason, doc,
                    currentUserService.username().orElse("admin"));
            ok(ra, (off ? "Списание" : "Поступление") + " товара № " + d.getId() + " проведено: " + F.count(doc.size(), "позиция", "позиции", "позиций") + ". Документ уйдёт в 1С.");
        } catch (RuntimeException e) {
            fail(ra, e);
        }
        return "redirect:" + back;
    }

    @PostMapping("/movements/{id}/delete")
    public String deleteMovement(@PathVariable Long id, @RequestParam(required = false) String back, RedirectAttributes ra) {
        try {
            if (!can(Role.ADMIN)) throw new IllegalStateException("Удалить строку документа может администратор");
            stockService.deleteMovement(id);
            ok(ra, "Строка удалена — остаток пересчитан, документ в 1С перепроведётся");
        } catch (RuntimeException e) {
            fail(ra, e);
        }
        return "redirect:" + (back != null && back.startsWith("/admin/stock") ? back : "/admin/stock?tab=moves");
    }

    @PostMapping("/sync-1c")
    public String syncStock(@RequestParam(required = false) String next, RedirectAttributes ra) {
        try {
            if (!can(Role.ADMIN)) throw new IllegalStateException("Сверку с 1С запускает администратор");
            OneCStockSyncService.SyncResult r = oneCStockSyncService.sync();
            ok(ra, "Сверили с 1С: товаров обновлено " + r.productsUpdated() + ", сырья " + r.ingredientsUpdated());
        } catch (RuntimeException e) {
            fail(ra, e);
        }
        return AdminSupport.back(next, "/admin/stock");
    }

    /** Старые адреса: журнал и форма документа — теперь вкладка «Движения» и окно на странице. */
    @GetMapping({"/movements", "/doc"})
    public String old() {
        return "redirect:/admin/stock?tab=moves";
    }
}
