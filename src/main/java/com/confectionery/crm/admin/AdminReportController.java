package com.confectionery.crm.admin;

import com.confectionery.crm.api.dto.ProductionCostDto;
import com.confectionery.crm.domain.*;
import com.confectionery.crm.repository.*;
import com.confectionery.crm.service.CurrentUserService;
import com.confectionery.crm.service.ProductionCostService;
import com.confectionery.crm.service.ReportXlsx;
import com.confectionery.crm.service.SalaryFundService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

import static com.confectionery.crm.admin.AdminReports.*;
import static com.confectionery.crm.admin.AdminSupport.*;

/**
 * Отчёты по макету (часть 4): страница отчётов — плитки месяца (продажа нетто, себестоимость проданного, закупки,
 * результат) и отчёты по группам с цифрой месяца; отчёт — период (сегодня, вчера, 7 дней, месяц, прошлый месяц, свой)
 * или день, плитки, таблица с итогом, выгрузка в Excel. Кто что видит — AdminReports.ALL; деньги в сводке,
 * себестоимости и закупках — администратор и бухгалтер.
 */
@Controller
@RequiredArgsConstructor
public class AdminReportController {

    private static final AdminFormat F = new AdminFormat();
    private static final String NB = AdminFormat.NB;

    private final OrderRepository orderRepository;
    private final ProductReturnRepository returnRepository;
    private final PurchaseRepository purchaseRepository;
    private final ProductionRequestRepository requestRepository;
    private final ProductRepository productRepository;
    private final ProductionCostService costService;
    private final SalaryFundService salaryFundService;
    private final CurrentUserService currentUserService;
    private final ReportXlsx xlsx;

    private Role role() {
        return currentUserService.role().orElse(null);
    }

    // ------------------------------------------------------------------ данные

    private Sales sales(LocalDate a, LocalDate b) {
        return AdminReports.sales(orderRepository.findByShippedAtBetween(a.atStartOfDay(), b.plusDays(1).atStartOfDay()),
                returnRepository.findByReturnDateBetweenOrderByCreatedAtDesc(a, b), a, b);
    }

    private Cost cost(LocalDate a, LocalDate b) {
        LocalDate today = LocalDate.now();
        if (a.isAfter(today)) return AdminReports.cost(List.of());
        return AdminReports.cost(costService.days(a, b.isAfter(today) ? today : b, true));
    }

    /** Себестоимость 1 шт по выпускам за 30 дней по день b. */
    private Map<Long, BigDecimal> unit(LocalDate b) {
        return cost(b.minusDays(29), b).unit();
    }

    private BigDecimal salaries(LocalDate a, LocalDate b) {
        Map<EmployeeCostGroup, BigDecimal> f = salaryFundService.periodFunds(a, b);
        return nz(f.get(EmployeeCostGroup.SALES)).add(nz(f.get(EmployeeCostGroup.ADMIN))).setScale(0, java.math.RoundingMode.HALF_UP);
    }

    private Shift shift(LocalDate d) {
        List<Order> open = orderRepository.findByStatusInOrderByCreatedAtAsc(AdminStock.OPEN);
        return AdminReports.shift(requestRepository.findByDocumentDateBetweenAndStatusNot(d, d, ProductionRequestStatus.CANCELLED),
                AdminStock.reserve(open, LocalDate.now()), productRepository.findAll());
    }

    // ------------------------------------------------------------------ список отчётов

    public record Card(Rep rep, String head) {
    }

    public record Group(String title, List<Card> cards) {
    }

    @GetMapping("/admin/reports")
    @Transactional(readOnly = true)
    public String reports(Model model) {
        Role role = role();
        boolean money = role == Role.ADMIN || role == Role.ACCOUNTANT;
        LocalDate today = LocalDate.now();
        LocalDate a = today.withDayOfMonth(1);
        String mon = AdminStaffController.MONTH[today.getMonthValue() - 1];
        List<Rep> reps = ALL.stream().filter(r -> r.visibleTo(role)).toList();
        Set<String> keys = new HashSet<>();
        reps.forEach(r -> keys.add(r.key()));
        Sales s = keys.contains("sales") || keys.contains("products") || keys.contains("clients") || keys.contains("returns") || money ? sales(a, today) : null;
        Cost c = keys.contains("cost") || money ? cost(a, today) : null;
        Pur pur = keys.contains("purchases") || money ? purchases(purchaseRepository.findByPurchaseDateBetweenOrderByCreatedAtDesc(a, today), a, today) : null;
        Shift sh = keys.contains("shift") ? shift(today) : null;
        Cogs cg = money ? cogs(s, unit(today)) : null;
        BigDecimal sal = money ? salaries(a, today) : null;
        BigDecimal res = money ? s.net().subtract(cg.cost()).subtract(sal) : null;
        Map<String, String> head = new HashMap<>();
        if (s != null) {
            long sold = s.prods.values().stream().filter(p -> p.q.signum() > 0).count();
            head.put("sales", F.money(s.net()) + " за " + mon + " · " + F.count(s.n, "заказ", "заказа", "заказов"));
            head.put("products", sold + NB + F.plural(sold, "товар продан", "товара продано", "товаров продано") + " · " + F.num(s.pcs) + NB + "шт");
            head.put("clients", F.count(s.cls.size(), "клиент", "клиента", "клиентов") + " · возвраты " + pctText(s.retPct() == null ? BigDecimal.ZERO : s.retPct(), 1));
            head.put("returns", F.num(s.rpcs) + NB + "шт вернули за " + mon);
        }
        if (sh != null) head.put("shift", "сегодня оприходовано " + F.num(sh.got()) + NB + "шт" + (sh.shortCount() > 0 ? " · не хватит на заказы: " + sh.shortCount() : ""));
        if (c != null) head.put("cost", "выпуск за " + mon + ": " + F.money(c.fg) + (c.fg.signum() > 0 ? " · наценка " + AdminProduction.pct(c.markup(), 0) : ""));
        if (pur != null) head.put("purchases", F.money(pur.total()) + " · " + F.count(pur.n(), "покупка", "покупки", "покупок"));
        head.put("staff", "табель за " + mon);
        if (res != null) head.put("summary", "результат за " + mon + ": " + (res.signum() < 0 ? "−" : "") + F.money(res.abs()));
        List<Group> groups = new ArrayList<>();
        for (Rep r : reps) {
            Group g = groups.isEmpty() || !groups.get(groups.size() - 1).title().equals(r.group()) ? null : groups.get(groups.size() - 1);
            if (g == null) groups.add(g = new Group(r.group(), new ArrayList<>()));
            g.cards().add(new Card(r, head.get(r.key())));
        }
        if (money) {
            model.addAttribute("kpis", List.of(
                    new Kpi("Продажа нетто · " + mon, big(s.net()), "продано " + F.money(s.sold) + ", возвраты " + F.money(s.ret), null),
                    new Kpi("Себестоимость проданного", big(cg.cost()), "наценка к продаже нетто " + AdminProduction.pct(AdminProduction.markup(s.net(), cg.cost()), 0)
                            + " · выпуск за " + mon + " — " + F.money(c.fg), null),
                    new Kpi("Закупки", big(pur.total()), F.count(pur.n(), "покупка", "покупки", "покупок") + " у поставщиков", null),
                    new Kpi("Результат (оценка)", big(res), "нетто − себестоимость проданного − оклады продаж и администрации", res.signum() < 0 ? "kpi--alert" : null)));
            model.addAttribute("kpiHrefs", List.of("/admin/reports/sales", "/admin/reports/summary", "/admin/reports/purchases", "/admin/reports/summary"));
            model.addAttribute("kpiIcons", List.of("cash", "production", "purchases", "reports"));
        }
        model.addAttribute("groups", groups);
        model.addAttribute("perText", "Цифры сверху — за " + mon + ", " + new Range("month", a, today).text() + ". В каждом отчёте период выбирается свой.");
        model.addAttribute("money", money);
        page(model, "Отчёты", "продажи, производство, закупки и деньги за период", "reports");
        return "admin/reports/list";
    }

    // ------------------------------------------------------------------ отчёт

    @GetMapping("/admin/reports/{key:[a-z]+}")
    @Transactional(readOnly = true)
    public String report(@PathVariable String key, @RequestParam(required = false) String per, @RequestParam(required = false) String from,
                         @RequestParam(required = false) String to, @RequestParam(required = false) String day, Model model, RedirectAttributes ra) {
        Rep r = AdminReports.byKey(key);
        if (r == null) {
            fail(ra, new IllegalArgumentException("Нет такого отчёта"));
            return "redirect:/admin/reports";
        }
        if (!r.visibleTo(role())) {
            fail(ra, new IllegalStateException("Этот отчёт вам недоступен"));
            return "redirect:/admin/reports";
        }
        if (r.href() != null) return "redirect:" + r.href();
        LocalDate today = LocalDate.now();
        Range rng = range(per, from, to, today);
        LocalDate d = AdminSupport.date(day, today);
        if (d.isAfter(today)) d = today;
        model.addAttribute("rep", r);
        model.addAttribute("rng", rng);
        model.addAttribute("pers", pers(key, rng, today));
        model.addAttribute("today", today);
        if (r.day()) {
            model.addAttribute("day", d);
            model.addAttribute("prevDay", d.minusDays(1));
            model.addAttribute("nextDay", d.isBefore(today) ? d.plusDays(1) : null);
            model.addAttribute("repSub", r.desc() + " · " + F.dml(d) + " " + d.getYear());
            model.addAttribute("xlsxHref", "/admin/reports/" + key + ".xlsx?day=" + d);
        } else {
            model.addAttribute("repSub", r.desc() + " · " + rng.text());
            model.addAttribute("xlsxHref", "/admin/reports/" + key + ".xlsx?" + rng.query());
        }
        Map<String, Object> out = new HashMap<>();
        Table t = build(key, rng, d, out);
        out.forEach(model::addAttribute);
        model.addAttribute("empty", t == null || t.rows().isEmpty());
        page(model, "Отчёты", r.title().toLowerCase(), "reports");
        return "admin/reports/view";
    }

    @GetMapping("/admin/reports/{key:[a-z]+}.xlsx")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> excel(@PathVariable String key, @RequestParam(required = false) String per, @RequestParam(required = false) String from,
                                        @RequestParam(required = false) String to, @RequestParam(required = false) String day) {
        Rep r = AdminReports.byKey(key);
        if (r == null || r.href() != null || !r.visibleTo(role())) return ResponseEntity.status(404).build();
        LocalDate today = LocalDate.now();
        Range rng = range(per, from, to, today);
        LocalDate d = AdminSupport.date(day, today);
        if (d.isAfter(today)) d = today;
        Table t = build(key, rng, d, new HashMap<>());
        String period = r.day() ? F.dml(d) + " " + d.getYear() : rng.text();
        byte[] bytes = xlsx.write(new ReportXlsx.Table(r.title(), period.replace(NB, " "), t.head(), t.types(), t.rows(), t.foot(), t.notes()));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(r.title() + " " + (r.day() ? d.toString() : rng.file()) + ".xlsx", StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).body(bytes);
    }

    /** Таблица отчёта для Excel (то же, что на странице) — и данные страницы в model. */
    public record Table(List<String> head, List<String> types, List<List<Object>> rows, List<Object> foot, List<String> notes) {
    }

    private static String plain(String html) {
        return html.replaceAll("<[^>]+>", "").replace(NB, " ");
    }

    private Table build(String key, Range rng, LocalDate day, Map<String, Object> out) {
        LocalDate a = rng.from(), b = rng.to();
        switch (key) {
            case "sales": {
                Sales s = sales(a, b);
                out.put("s", s);
                out.put("kpis", List.of(
                        new Kpi("Итого за минусом возвратов", big(s.net()), F.money(s.net()), null),
                        new Kpi("Выдано заказов", String.valueOf(s.n), s.n > 0 ? "средний заказ " + F.money(s.sold.divide(BigDecimal.valueOf(s.n), 0, java.math.RoundingMode.HALF_UP)) : "выдач не было", null),
                        new Kpi("Штук продано", F.num(s.pcs), "возвращено " + F.num(s.rpcs) + NB + "шт", null),
                        new Kpi("Возвраты", big(s.ret), s.retPct() == null ? "—" : pctText(s.retPct(), 1) + " от продаж",
                                s.retPct() != null && s.retPct().compareTo(BigDecimal.TEN) >= 0 ? "kpi--warm" : null)));
                List<Day> rev = new ArrayList<>(s.days);
                Collections.reverse(rev);
                out.put("daysDesc", rev);
                List<Map<String, Object>> js = new ArrayList<>();
                for (Day x : s.days) js.add(Map.of("d", x.d.toString(), "sold", x.sold, "ret", x.ret, "n", x.n, "pcs", x.pcs));
                out.put("chartJson", AdminJson.of(Map.of("days", js, "today", LocalDate.now().toString())));
                List<List<Object>> rows = new ArrayList<>();
                for (Day x : rev) rows.add(Arrays.asList(F.cap(F.wday(x.d)) + ", " + F.dm(x.d).replace(NB, " "), x.n, x.pcs, x.sold, x.ret.negate(), x.net()));
                return new Table(List.of("День", "Заказов", "Штук", "Продано", "Возвраты", "Итого"), List.of("text", "int", "qty", "money", "money", "money"),
                        rows, Arrays.asList("Итого", s.n, s.pcs, s.sold, s.ret.negate(), s.net()),
                        List.of("Продано — выданные заказы по дню выдачи; возвраты — по дню, когда их приняли."));
            }
            case "products": {
                Sales s = sales(a, b);
                List<Prod> list = s.prodList();
                out.put("s", s);
                out.put("list", list);
                out.put("max", list.isEmpty() ? BigDecimal.ZERO : list.get(0).net());
                if (!list.isEmpty()) {
                    long sold = list.stream().filter(p -> p.q.signum() > 0).count();
                    long inCat = productRepository.findAll().stream().filter(p -> p.isActive() && !p.isSemiFinished()).count();
                    Prod top = list.get(0);
                    out.put("kpis", List.of(
                            new Kpi("Итого за минусом возвратов", big(s.net()), F.money(s.net()), null),
                            new Kpi("Продано", pcs(s.pcs), "возвращено " + F.num(s.rpcs) + NB + "шт", null),
                            new Kpi("Товаров продано", String.valueOf(sold), "из " + inCat + " в каталоге", null),
                            new Kpi("Лучше всех", esc(AdminRequests.shortName(top.p.getName())),
                                    F.money(top.net()) + " · " + pctText(share(top.net(), s.net()), 0) + " выручки", null)));
                }
                List<List<Object>> rows = new ArrayList<>();
                for (Prod p : list) rows.add(Arrays.asList(p.p.getName(), p.q, p.rq, p.retPct() == null || p.rq.signum() == 0 ? "—" : pctText(p.retPct(), 1), p.net()));
                return new Table(List.of("Товар", "Продано, шт", "Возврат, шт", "% возврата", "Итого"), List.of("text", "qty", "qty", "pct", "money"), rows,
                        Arrays.asList("Итого", s.pcs, s.rpcs, s.pcs.signum() > 0 ? pctText(share(s.rpcs, s.pcs), 1) : "—", s.net()),
                        List.of("Сначала — что принесло больше денег. % возврата — какую часть проданного вернули."));
            }
            case "clients": {
                Sales s = sales(a, b);
                List<Cl> list = s.clList();
                long bad = list.stream().filter(c -> c.retPct() != null && c.retPct().compareTo(BigDecimal.TEN) >= 0).count();
                out.put("s", s);
                out.put("list", list);
                out.put("max", list.isEmpty() ? BigDecimal.ZERO : list.get(0).net());
                if (!list.isEmpty()) out.put("kpis", List.of(
                        new Kpi("Итого за минусом возвратов", big(s.net()), F.money(s.net()), null),
                        new Kpi("Клиентов", String.valueOf(list.size()), F.count(s.n, "заказ", "заказа", "заказов") + " выдано", null),
                        new Kpi("Возвраты", big(s.ret), s.retPct() == null ? "—" : pctText(s.retPct(), 1) + " от продаж", null),
                        new Kpi("Возврат от 10 %", String.valueOf(bad), bad > 0 ? "клиентов — посмотрите, что они возвращают" : "таких клиентов нет", bad > 0 ? "kpi--warm" : null)));
                List<List<Object>> rows = new ArrayList<>();
                for (Cl c : list) rows.add(Arrays.asList(c.name, c.n, c.s, c.r.negate(), c.retPct() == null || c.r.signum() == 0 ? "—" : pctText(c.retPct(), 1), c.net()));
                return new Table(List.of("Клиент", "Заказов", "Продано", "Возвраты", "% возврата", "Итого"), List.of("text", "int", "money", "money", "pct", "money"), rows,
                        Arrays.asList("Итого", s.n, s.sold, s.ret.negate(), s.retPct() == null ? "—" : pctText(s.retPct(), 1), s.net()),
                        List.of("% возврата — какую часть купленного клиент вернул за период."));
            }
            case "shift": {
                Shift sh = shift(day);
                out.put("sh", sh);
                out.put("kpis", List.of(
                        new Kpi("Оприходовано", pcs(sh.got()), sh.plan().signum() > 0 ? "по плану " + F.num(sh.plan()) + NB + "шт" : "в этот день заявок не было", null),
                        new Kpi("Не хватит на заказы", String.valueOf(sh.shortCount()), sh.shortCount() > 0
                                ? F.plural(sh.shortCount(), "товар", "товара", "товаров") + " — остатка меньше, чем в открытых заказах" : "остатка хватает на все заказы",
                                sh.shortCount() > 0 ? "kpi--alert" : null),
                        new Kpi("В открытых заказах", pcs(sh.inOrders()), "новые, принятые, собираемые, готовые и не забранные", null),
                        new Kpi("Заявок дня", String.valueOf(sh.reqs()), "<a class=\"link\" href=\"/admin/production?date=" + day + "\">Производство за день</a>", null)));
                List<List<Object>> rows = new ArrayList<>();
                for (ShiftRow x : sh.rows()) rows.add(Arrays.asList(x.p().getName(), x.plan(), x.got(), x.stock(), x.rq(),
                        x.shortage() ? "не хватает " + F.num(x.free().negate()) : x.rq().signum() > 0 ? "хватает" : "—"));
                return new Table(List.of("Товар", "План", "Оприходовано", "Остаток", "В заказах", "Итог"), List.of("text", "qty", "qty", "qty", "qty", "text"), rows, null,
                        List.of("Остаток и «в заказах» — на момент выгрузки. Итог — хватает ли остатка на открытые заказы."));
            }
            case "cost": {
                Cost c = cost(a, b);
                List<CostProd> list = c.list(), low = c.low();
                out.put("c", c);
                out.put("list", list);
                if (!list.isEmpty()) out.put("kpis", List.of(
                        new Kpi("Себестоимость выпуска", big(c.fg), F.money(c.fg) + (c.semi.signum() > 0 ? " · полуфабрикаты " + F.money(c.semi) + " отдельно" : ""), null),
                        new Kpi("По цене продажи", big(c.sale), "наценка <b>" + AdminProduction.pct(c.markup(), 0) + "</b>", null),
                        new Kpi("Выпущено", pcs(c.units), c.finished() + NB + "товаров · оприходованное", null),
                        new Kpi("Наценка ниже 20 %", String.valueOf(low.size()), low.isEmpty() ? "у всех товаров выше"
                                : esc(String.join(", ", low.stream().limit(3).map(x -> AdminRequests.shortName(x.name)).toList())), low.isEmpty() ? null : "kpi--warm")));
                List<List<Object>> rows = new ArrayList<>();
                for (CostProd p : list) rows.add(Arrays.asList(p.name + (p.semi ? " (ПФ)" : ""), p.q, p.cost, p.unit(), p.sale, p.markup() == null ? "—" : AdminProduction.pct(p.markup(), 0).replace(NB, " ")));
                return new Table(List.of("Товар", "Выпущено, шт", "Себестоимость", "За 1 шт", "Цена", "Наценка"), List.of("text", "qty", "money", "money", "money", "pct"), rows,
                        Arrays.asList("Итого", c.units, c.fg.add(c.semi), null, c.sale, AdminProduction.pct(c.markup(), 0).replace(NB, " ")),
                        List.of("Только оприходованное. Себестоимость — сырьё по цене последней покупки, зарплата бригад и накладные цеха — как на «Производстве» за каждый день."));
            }
            case "purchases": {
                Pur p = purchases(purchaseRepository.findByPurchaseDateBetweenOrderByCreatedAtDesc(a, b), a, b);
                out.put("pur", p);
                out.put("max", p.top() == null ? BigDecimal.ZERO : p.top().s);
                if (p.top() != null) out.put("kpis", List.of(
                        new Kpi("Закуплено", big(p.total()), F.money(p.total()), null),
                        new Kpi("Покупок", String.valueOf(p.n()), F.count(p.list().size(), "поставщик", "поставщика", "поставщиков"), null),
                        new Kpi("Больше всего", esc(p.top().name), F.money(p.top().s) + " · " + pctText(share(p.top().s, p.total()), 0), null),
                        new Kpi("Не ушли в 1С", String.valueOf(p.err()), p.err() > 0 ? "<a class=\"link\" href=\"/admin/purchases?seg=err&per=all\">Посмотреть</a>" : "все покупки в 1С",
                                p.err() > 0 ? "kpi--alert" : null)));
                List<List<Object>> rows = new ArrayList<>();
                for (Sup x : p.list()) rows.add(Arrays.asList(x.name, x.n, x.last == null ? "" : F.dm(x.last).replace(NB, " "), x.s));
                return new Table(List.of("Поставщик", "Покупок", "Последняя", "Сумма"), List.of("text", "int", "text", "money"), rows,
                        Arrays.asList("Итого", p.n(), "", p.total()), List.of("Суммы — по накладным поставщиков, по дате покупки."));
            }
            case "summary": {
                Sales s = sales(a, b);
                Cogs cg = cogs(s, unit(b));
                BigDecimal sal = salaries(a, b), net = s.net(), res = net.subtract(cg.cost()).subtract(sal);
                out.put("s", s);
                out.put("cg", cg);
                out.put("kpis", List.of(
                        new Kpi("Продажа нетто", big(net), "продано " + F.money(s.sold) + " − возвраты " + F.money(s.ret), null),
                        new Kpi("Себестоимость проданного", big(cg.cost()), (cg.cost().signum() > 0 ? "наценка " + AdminProduction.pct(AdminProduction.markup(net, cg.cost()), 0) + " · " : "")
                                + "проданные штуки × себестоимость 1 шт", null),
                        new Kpi("Оклады", big(sal), "продажи и администрация за рабочие дни периода", null),
                        new Kpi("Результат", big(res), "оценка · " + (net.signum() != 0 ? F.dec(res.multiply(BigDecimal.valueOf(100)).divide(net, 0, java.math.RoundingMode.HALF_UP), 0) + NB + "% от продажи нетто" : "—"),
                                res.signum() < 0 ? "kpi--alert" : null)));
                BigDecimal max = s.sold.max(BigDecimal.ONE);
                List<Line> lines = List.of(new Line("Продано", s.sold, "", bar(s.sold, max)), new Line("Возвраты", s.ret.negate(), s.ret.signum() > 0 ? "is-bad" : "", bar(s.ret, max)),
                        new Line("Себестоимость проданного", cg.cost().negate(), "", bar(cg.cost(), max)), new Line("Оклады продаж и администрации", sal.negate(), "", bar(sal, max)),
                        new Line("Результат (оценка)", res, "su-t " + (res.signum() < 0 ? "is-bad" : "is-good"), bar(res.abs(), max)));
                out.put("lines", lines);
                List<List<Object>> rows = new ArrayList<>();
                for (Line x : lines) rows.add(Arrays.asList(x.label(), x.v()));
                return new Table(List.of("Статья", "Сумма"), List.of("text", "money"), rows, null,
                        List.of("Оценка. Себестоимость проданного — проданные штуки (за минусом возвращённых) × себестоимость 1 шт по выпускам за 30 дней: сырьё, бригады, накладные цеха.",
                                "Аренда, коммунальные, налоги и закупки впрок не учтены."));
            }
            default:
                return null;
        }
    }

    /** Строка сводки: подпись, сумма со знаком, класс, ширина полоски (% от «продано», не больше 100). */
    public record Line(String label, BigDecimal v, String cls, int w) {
        public int width() {
            return Math.min(100, Math.max(1, w));
        }
    }
}
