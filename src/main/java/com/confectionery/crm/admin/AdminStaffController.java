package com.confectionery.crm.admin;

import com.confectionery.crm.domain.*;
import com.confectionery.crm.repository.EmployeeRepository;
import com.confectionery.crm.repository.ProductionBrigadeMemberRepository;
import com.confectionery.crm.repository.ProductionRequestRepository;
import com.confectionery.crm.repository.TimesheetMarkRepository;
import com.confectionery.crm.service.*;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.function.Predicate;

import static com.confectionery.crm.admin.AdminSupport.*;

/**
 * Команда по макету (часть 4): сотрудники цеха и офиса — список с группами затрат, карточка с табелем месяца и
 * бригадами, «Уволить» / «Вернуть на работу», форма с проверками; табель по дням месяца (отметка нажатием, при
 * сохранении пустые прошедшие дни — «не пришёл»), выгрузка в Excel; входы в CRM (роль, пароль, доступ) и таблица
 * разделов по ролям; свой пароль. Ставки, оклады и начисления видят администратор и бухгалтер, входы ведут они же.
 */
@Controller
@RequiredArgsConstructor
public class AdminStaffController {

    private static final AdminFormat F = new AdminFormat();
    private static final String NB = AdminFormat.NB;
    static final String[] IN_MONTH = {"январе", "феврале", "марте", "апреле", "мае", "июне", "июле", "августе", "сентябре",
            "октябре", "ноябре", "декабре"};
    static final String[] MONTH = {"январь", "февраль", "март", "апрель", "май", "июнь", "июль", "август", "сентябрь",
            "октябрь", "ноябрь", "декабрь"};

    private final EmployeeService employeeService;
    private final EmployeeRepository employeeRepository;
    private final OneCEmployeeImportService importService;
    private final TimesheetService timesheetService;
    private final TimesheetMarkRepository markRepository;
    private final ProductionBrigadeMemberRepository brigadeRepository;
    private final ProductionRequestRepository requestRepository;
    private final UserService userService;
    private final CurrentUserService currentUserService;
    private final PasswordEncoder passwordEncoder;

    private boolean money() {
        return currentUserService.hasRole(Role.ADMIN, Role.ACCOUNTANT);
    }

    private AdminTeam.Month month(YearMonth ym) {
        return AdminTeam.Month.of(ym, markRepository.findForPeriod(ym.atDay(1), ym.atEndOfMonth()),
                brigadeRepository.findForPeriod(ym.atDay(1), ym.atEndOfMonth(), ProductionRequestStatus.CANCELLED));
    }

    private void tabs(Model model, String tab) {
        model.addAttribute("tab", tab);
        model.addAttribute("canUsers", money());
        model.addAttribute("money", money());
    }

    static String monthTitle(YearMonth ym) {
        return F.cap(MONTH[ym.getMonthValue() - 1]) + " " + ym.getYear();
    }

    // ------------------------------------------------------------------ список

    public record Row(Employee e, AdminTeam.Line t, AdminTeam.Last last) {
        public String initials() {
            return AdminTeam.initials(e.getFullName());
        }

        public String sub() {
            return (e.getPosition() != null ? e.getPosition() : "") + (!e.isActive() ? " · уволен" + AdminTeam.a(e) : "");
        }

        public BigDecimal dayRate() {
            return SalaryFundService.dailyRate(e, LocalDate.now());
        }
    }

    /** Строка таблицы: сотрудник или заголовок группы (head != null). */
    public record Item(Row row, EmployeeCostGroup head, int count) {
        public String headText() {
            return count + NB + F.plural(count, "человек", "человека", "человек") + " · " + AdminTeam.hint(head);
        }
    }

    public record Chip(String key, String title, long n, boolean alert, String href, boolean on) {
    }

    static String enc(String s) {
        return java.net.URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    static final List<String[]> SORTS = List.of(new String[]{"grp", "По группам затрат"}, new String[]{"name", "По ФИО"},
            new String[]{"shifts", "Больше смен"}, new String[]{"rate", "Ставка: выше"});

    @GetMapping("/admin/staff")
    @Transactional(readOnly = true)
    public String list(@RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "all") String g,
                       @RequestParam(defaultValue = "grp") String sort, Model model) {
        boolean money = money();
        LocalDate today = LocalDate.now();
        YearMonth ym = YearMonth.from(today);
        AdminTeam.Month m = month(ym);
        List<Employee> all = employeeRepository.findAll();
        List<Employee> on = all.stream().filter(Employee::isActive).toList();
        Map<EmployeeCostGroup, Integer> byG = new EnumMap<>(EmployeeCostGroup.class);
        on.forEach(e -> byG.merge(e.effectiveCostGroup(), 1, Integer::sum));

        // плитки
        Map<Long, AdminTeam.Line> lines = new HashMap<>();
        for (Employee e : all) lines.put(e.getId(), AdminTeam.line(e, m, today, money));
        List<AdminTeam.Line> onLines = on.stream().map(e -> lines.get(e.getId())).toList();
        BigDecimal pay = BigDecimal.ZERO, payB = BigDecimal.ZERO;
        int emptyCells = 0, emptyPeople = 0;
        LocalDate firstEmpty = null;
        for (AdminTeam.Line x : onLines) {
            if (money) {
                pay = pay.add(x.pay());
                if (x.e().effectiveCostGroup() == EmployeeCostGroup.BRIGADE) payB = payB.add(x.pay());
            }
            emptyCells += x.empty();
            if (x.empty() > 0) emptyPeople++;
            LocalDate f = x.firstMiss();
            if (f != null && (firstEmpty == null || f.isBefore(firstEmpty))) firstEmpty = f;
        }
        List<ProductionRequest> reqs = requestRepository.findByDocumentDateBetweenAndStatusNot(today, today, ProductionRequestStatus.CANCELLED);
        reqs = reqs.stream().sorted(Comparator.comparing(ProductionRequest::getId)).toList();
        Set<Long> withBrig = new HashSet<>();
        m.brig().values().forEach(x -> x.getOrDefault(today, List.of()).forEach(r -> withBrig.add(r.getId())));
        model.addAttribute("onCount", on.size());
        model.addAttribute("onText", String.join(" · ", AdminTeam.ORDER.stream().filter(byG::containsKey)
                .map(x -> x.getLabel().toLowerCase() + " " + byG.get(x)).toList()));
        model.addAttribute("inBrig", m.inBrigades(today));
        model.addAttribute("brigOf", byG.getOrDefault(EmployeeCostGroup.BRIGADE, 0));
        model.addAttribute("reqsText", reqs.isEmpty() ? "сегодня заявок нет"
                : "заявки № " + String.join(", ", reqs.stream().map(r -> String.valueOf(r.getId())).toList())
                + (reqs.stream().anyMatch(r -> !withBrig.contains(r.getId())) ? " · без бригады: № " + String.join(", ",
                reqs.stream().filter(r -> !withBrig.contains(r.getId())).map(r -> String.valueOf(r.getId())).toList()) : ""));
        model.addAttribute("pay", pay);
        model.addAttribute("payB", payB);
        model.addAttribute("emptyCells", emptyCells);
        model.addAttribute("emptyText", emptyCells == 0 ? "все прошедшие дни месяца отмечены"
                : "не отмечено с " + F.dm(firstEmpty) + " у " + emptyPeople + NB + F.plural(emptyPeople, "человека", "человек", "человек")
                + " — при сохранении станет «не пришёл»");

        // отборы, порядок
        String nq = AdminRequests.norm(q);
        List<Employee> base = all.stream().filter(e -> nq.isEmpty()
                || AdminRequests.norm(e.getFullName() + " " + (e.getPosition() == null ? "" : e.getPosition())).contains(nq)).toList();
        Map<String, Predicate<Employee>> segs = new LinkedHashMap<>();
        segs.put("all", Employee::isActive);
        for (EmployeeCostGroup x : AdminTeam.ORDER) segs.put(AdminTeam.key(x), e -> e.isActive() && e.effectiveCostGroup() == x);
        segs.put("norate", e -> e.isActive() && e.getDailyRate() == null && e.getMonthlySalary() == null);
        segs.put("off", e -> !e.isActive());
        if (!segs.containsKey(g)) g = "all";
        List<Chip> chips = new ArrayList<>();
        for (Map.Entry<String, Predicate<Employee>> s : segs.entrySet()) {
            long n = base.stream().filter(s.getValue()).count();
            String title = switch (s.getKey()) {
                case "all" -> "Все";
                case "norate" -> "Без ставки";
                case "off" -> "Уволенные";
                default -> AdminTeam.group(s.getKey()).getLabel();
            };
            if (s.getKey().equals("norate") && (n == 0 || !money)) continue;
            chips.add(new Chip(s.getKey(), title, n, s.getKey().equals("norate"),
                    "/admin/staff?g=" + s.getKey() + (q.isBlank() ? "" : "&q=" + enc(q)) + (sort.equals("grp") ? "" : "&sort=" + enc(sort)),
                    s.getKey().equals(g)));
        }
        if (g.equals("norate") && !money) g = "all";
        Map<Long, AdminTeam.Last> lasts = AdminTeam.lasts(
                brigadeRepository.findForPeriod(today.minusDays(120), today, ProductionRequestStatus.CANCELLED),
                markRepository.findForPeriod(today.minusDays(120), today), today);
        Predicate<Employee> seg = segs.get(g);
        List<Row> rows = new ArrayList<>(base.stream().filter(seg).map(e -> new Row(e, lines.get(e.getId()), lasts.get(e.getId()))).toList());
        String srt = SORTS.stream().anyMatch(s -> s[0].equals(sort)) && (money || !sort.equals("rate")) ? sort : "grp";
        Comparator<Row> byName = (x, y) -> AdminTeam.BY_NAME.compare(x.e(), y.e());
        rows.sort(switch (srt) {
            case "name" -> byName;
            case "shifts" -> Comparator.comparing((Row r) -> r.t().worked()).reversed().thenComparing(byName);
            case "rate" -> Comparator.comparing((Row r) -> rate(r.e(), today)).reversed().thenComparing(byName);
            default -> Comparator.comparingInt((Row r) -> AdminTeam.ORDER.indexOf(r.e().effectiveCostGroup())).thenComparing(byName);
        });
        List<Item> items = new ArrayList<>();
        EmployeeCostGroup lastG = null;
        boolean heads = srt.equals("grp") && !g.equals("off");
        for (Row r : rows) {
            EmployeeCostGroup eg = r.e().effectiveCostGroup();
            if (heads && eg != lastG) {
                lastG = eg;
                items.add(new Item(null, eg, (int) rows.stream().filter(x -> x.e().effectiveCostGroup() == eg).count()));
            }
            items.add(new Item(r, null, 0));
        }
        model.addAttribute("items", items);
        model.addAttribute("chips", chips);
        model.addAttribute("q", q);
        model.addAttribute("g", g);
        model.addAttribute("sort", srt);
        model.addAttribute("sorts", money ? SORTS : SORTS.subList(0, 3));
        model.addAttribute("inMonth", IN_MONTH[ym.getMonthValue() - 1]);
        model.addAttribute("monthName", MONTH[ym.getMonthValue() - 1]);
        model.addAttribute("offCount", all.size() - on.size());
        tabs(model, "list");
        page(model, "Сотрудники", "цех, склад и офис: ставки, табель и входы в CRM", "staff");
        return "admin/staff/list";
    }

    private static BigDecimal rate(Employee e, LocalDate d) {
        BigDecimal r = SalaryFundService.dailyRate(e, d);
        return r == null ? BigDecimal.ZERO : r;
    }

    @PostMapping("/admin/staff/import-1c")
    public String import1c(@RequestParam(required = false) String next, RedirectAttributes ra) {
        try {
            OneCEmployeeImportService.ImportResult r = importService.importAll();
            ok(ra, "Из 1С: всего " + r.total() + ", добавлено " + r.created() + ", обновлено " + r.updated());
        } catch (RuntimeException e) {
            fail(ra, e);
        }
        return AdminSupport.back(next, "/admin/staff");
    }

    // ------------------------------------------------------------------ карточка

    /** Бригада в карточке: заявка, что выпускали, сколько человек, сколько ушло в себестоимость за его смену. */
    public record Shift(ProductionRequest r, String what, int people, BigDecimal cost) {
    }

    @GetMapping("/admin/staff/{id:\\d+}")
    @Transactional(readOnly = true)
    public String view(@PathVariable Long id, @RequestParam(required = false) String month, Model model, RedirectAttributes ra) {
        Employee e = employeeRepository.findById(id).orElse(null);
        if (e == null) {
            fail(ra, new IllegalArgumentException("Такого сотрудника нет"));
            return "redirect:/admin/staff";
        }
        boolean money = money();
        LocalDate today = LocalDate.now();
        YearMonth base = AdminTeam.baseMonth(e, today), now = YearMonth.from(today);
        YearMonth ym = parse(month, base);
        if (ym.isAfter(now)) ym = now;
        AdminTeam.Line t = AdminTeam.line(e, month(ym), today, money);
        AdminTeam.Line cur = ym.equals(base) ? t : AdminTeam.line(e, month(base), today, money);
        LocalDate upto = base.equals(now) ? today : e.getFiredAt();

        List<ProductionBrigadeMember> mine = brigadeRepository.findForEmployee(id, today, ProductionRequestStatus.CANCELLED);
        AdminTeam.Last last = mine.isEmpty() ? markRepository.findFirstByEmployeeIdAndStatusInAndDateLessThanEqualOrderByDateDesc(id, List.of("P", "H"), today)
                .map(x -> new AdminTeam.Last(x.getDate(), null)).orElse(null)
                : new AdminTeam.Last(mine.get(0).getRequest().getDocumentDate(), mine.get(0).getRequest());
        List<ProductionRequest> reqs = mine.stream().map(ProductionBrigadeMember::getRequest).limit(10).toList();
        Map<Long, Integer> people = new HashMap<>();
        if (!reqs.isEmpty()) {
            for (ProductionBrigadeMember x : brigadeRepository.findByRequestIds(reqs.stream().map(ProductionRequest::getId).toList())) {
                people.merge(x.getRequest().getId(), 1, Integer::sum);
            }
        }
        List<Shift> shifts = reqs.stream().map(r -> {
            List<String> what = r.getLines().stream().map(l -> AdminRequests.shortName(l.getProduct().getName())).distinct().toList();
            String w = String.join(", ", what.subList(0, Math.min(3, what.size()))) + (what.size() > 3 ? " и ещё " + (what.size() - 3) : "");
            return new Shift(r, w, people.getOrDefault(r.getId(), 0), money ? SalaryFundService.dailyRate(e, r.getDocumentDate()) : null);
        }).toList();

        User user = userService.findAll().stream().filter(u -> AdminTeam.normName(u.getFullName()).equals(AdminTeam.normName(e.getFullName())))
                .findFirst().orElse(null);
        int lead = ym.atDay(1).getDayOfWeek().getValue() - 1;
        model.addAttribute("e", e);
        model.addAttribute("a", AdminTeam.a(e));
        model.addAttribute("initials", AdminTeam.initials(e.getFullName()));
        model.addAttribute("groupHint", AdminTeam.hint(e.effectiveCostGroup()));
        model.addAttribute("t", t);
        model.addAttribute("cur", cur);
        model.addAttribute("ym", ym);
        model.addAttribute("ymTitle", monthTitle(ym).toLowerCase());
        model.addAttribute("prev", ym.minusMonths(1).toString());
        model.addAttribute("next", ym.isBefore(now) ? ym.plusMonths(1).toString() : null);
        model.addAttribute("blanks", Collections.nCopies(lead, 0));
        model.addAttribute("inMonth", IN_MONTH[base.getMonthValue() - 1]);
        model.addAttribute("baseName", MONTH[base.getMonthValue() - 1]);
        model.addAttribute("asOf", upto != null && !upto.equals(today) ? "по " + F.dml(upto) + ", день увольнения" : "на " + F.dml(today));
        model.addAttribute("last", last);
        model.addAttribute("shifts", shifts);
        model.addAttribute("shiftCount", mine.size());
        model.addAttribute("user", user);
        model.addAttribute("workDays", SalaryFundService.workingDays(now));
        model.addAttribute("dayRate", SalaryFundService.dailyRate(e, today));
        model.addAttribute("monthGen", AdminFormat.monthGen(now.getMonthValue()));
        model.addAttribute("money", money);
        page(model, "Сотрудники", "", "staff");
        return "admin/staff/view";
    }

    private static YearMonth parse(String m, YearMonth fallback) {
        try {
            return m == null || m.isBlank() ? fallback : YearMonth.parse(m);
        } catch (Exception e) {
            return fallback;
        }
    }

    @PostMapping("/admin/staff/{id:\\d+}/fire")
    public String fire(@PathVariable Long id, RedirectAttributes ra) {
        try {
            Employee e = employeeService.setWorking(id, false);
            ok(ra, "«" + e.getFullName() + "» уволен" + AdminTeam.a(e));
            ra.addFlashAttribute("toastUndo", "/admin/staff/" + id + "/return");
        } catch (RuntimeException ex) {
            fail(ra, ex);
        }
        return "redirect:/admin/staff/" + id;
    }

    @PostMapping("/admin/staff/{id:\\d+}/return")
    public String back(@PathVariable Long id, RedirectAttributes ra) {
        try {
            Employee e = employeeService.setWorking(id, true);
            ok(ra, "«" + e.getFullName() + "» снова в списке работающих");
        } catch (RuntimeException ex) {
            fail(ra, ex);
        }
        return "redirect:/admin/staff/" + id;
    }

    // ------------------------------------------------------------------ форма

    @GetMapping({"/admin/staff/new", "/admin/staff/{id:\\d+}/edit"})
    @Transactional(readOnly = true)
    public String form(@PathVariable(required = false) Long id, Model model, RedirectAttributes ra) {
        Employee e = id == null ? new Employee() : employeeRepository.findById(id).orElse(null);
        if (e == null) {
            fail(ra, new IllegalArgumentException("Такого сотрудника нет"));
            return "redirect:/admin/staff";
        }
        return form(e, AdminTeam.Form.of(e), Map.of(), false, model);
    }

    private String form(Employee e, AdminTeam.Form f, Map<String, String> errors, boolean tried, Model model) {
        model.addAttribute("e", e);
        model.addAttribute("f", f);
        model.addAttribute("errors", errors);
        model.addAttribute("tried", tried);
        model.addAttribute("groups", AdminTeam.ORDER);
        model.addAttribute("positions", employeeRepository.findAll().stream().map(Employee::getPosition).filter(Objects::nonNull)
                .distinct().sorted(AdminTeam.RU::compare).toList());
        model.addAttribute("money", money());
        model.addAttribute("workDays", SalaryFundService.workingDays(YearMonth.now()));
        model.addAttribute("monthGen", AdminFormat.monthGen(LocalDate.now().getMonthValue()));
        Map<String, String> hints = new LinkedHashMap<>();
        for (EmployeeCostGroup x : AdminTeam.ORDER) hints.put(AdminTeam.key(x), AdminTeam.hint(x));
        model.addAttribute("hints", hints);
        int wd = SalaryFundService.workingDays(YearMonth.now());
        String gen = AdminFormat.monthGen(LocalDate.now().getMonthValue());
        model.addAttribute("how", AdminTeam.how(f, wd, gen));
        model.addAttribute("initials", AdminTeam.initials(f.name()));
        Map<String, Object> js = new LinkedHashMap<>();
        Map<String, String> labels = new LinkedHashMap<>();
        for (EmployeeCostGroup x : AdminTeam.ORDER) labels.put(AdminTeam.key(x), x.getLabel());
        js.put("labels", labels);
        js.put("hints", hints);
        js.put("workDays", wd);
        js.put("monthGen", gen);
        js.put("money", money());
        js.put("names", employeeRepository.findAll().stream().filter(x -> !Objects.equals(x.getId(), e.getId()))
                .map(x -> AdminTeam.normName(x.getFullName())).toList());
        model.addAttribute("formJson", AdminJson.of(js));
        model.addAttribute("grpOpts", AdminTeam.ORDER.stream().map(x -> new String[]{AdminTeam.key(x), x.getLabel()}).toList());
        model.addAttribute("autoLabel", EmployeeCostGroup.byPosition(f.pos()).getLabel());
        model.addAttribute("effKey", AdminTeam.key(f.effective()));
        model.addAttribute("groupHintText", F.cap(AdminTeam.hint(f.effective())) + ".");
        page(model, "Сотрудники", "", "staff");
        return "admin/staff/form";
    }

    @PostMapping({"/admin/staff", "/admin/staff/{id:\\d+}"})
    public String save(@PathVariable(required = false) Long id, @RequestParam Map<String, String> p, Model model, RedirectAttributes ra) {
        boolean money = money();
        Employee e = id == null ? new Employee() : employeeRepository.findById(id).orElse(null);
        if (e == null) {
            fail(ra, new IllegalArgumentException("Такого сотрудника нет"));
            return "redirect:/admin/staff";
        }
        AdminTeam.Form f = AdminTeam.Form.of(p);
        Map<String, String> errors = AdminTeam.errors(f, id, employeeRepository.findAll(), money);
        if (!errors.isEmpty()) return form(e, f, errors, true, model);
        try {
            Employee saved = employeeService.save(id, f.name(), f.pos(), f.phone(), f.rateNum(), id == null || f.on(), money,
                    f.salNum(), f.group());
            ok(ra, id == null ? "Сотрудник «" + saved.getFullName() + "» добавлен"
                    + (saved.effectiveCostGroup() == EmployeeCostGroup.BRIGADE ? " — его уже можно поставить в бригаду" : "")
                    : "Карточка «" + saved.getFullName() + "» сохранена");
            String next = p.get("next");
            return next != null && next.matches("/admin/[\\w/?=&.-]*") ? "redirect:" + next : "redirect:/admin/staff/" + saved.getId();
        } catch (RuntimeException ex) {
            return form(e, f, Map.of("save", ex.getMessage() == null ? "Не получилось — попробуйте ещё раз" : ex.getMessage()), true, model);
        }
    }

    // ------------------------------------------------------------------ табель

    @GetMapping("/admin/staff/timesheet")
    @Transactional(readOnly = true)
    public String timesheet(@RequestParam(required = false) String month, Model model) {
        boolean money = money();
        LocalDate today = LocalDate.now();
        YearMonth now = YearMonth.from(today), ym = parse(month, now);
        if (ym.isAfter(now)) ym = now;
        AdminTeam.Month m = month(ym);
        List<AdminTeam.Line> lines = employeeRepository.findAll().stream().filter(e -> AdminTeam.shown(e, m))
                .sorted(AdminTeam.BY_GROUP).map(e -> AdminTeam.line(e, m, today, money)).toList();
        Map<EmployeeCostGroup, Integer> counts = new EnumMap<>(EmployeeCostGroup.class);
        lines.forEach(x -> counts.merge(x.e().effectiveCostGroup(), 1, Integer::sum));
        List<Chip> chips = new ArrayList<>();
        chips.add(new Chip("all", "Все", lines.size(), false, null, true));
        for (EmployeeCostGroup x : AdminTeam.ORDER) if (counts.containsKey(x)) chips.add(new Chip(AdminTeam.key(x), x.getLabel(), counts.get(x), false, null, false));
        int wd = SalaryFundService.workingDays(ym);
        long passed = m.days().stream().filter(d -> !d.isAfter(today) && d.getDayOfWeek() != java.time.DayOfWeek.SUNDAY).count();
        BigDecimal worked = BigDecimal.ZERO, pay = money ? BigDecimal.ZERO : null;
        int absent = 0, empty = 0;
        for (AdminTeam.Line x : lines) {
            worked = worked.add(x.worked());
            absent += x.absent();
            empty += x.empty();
            if (money) pay = pay.add(x.pay());
        }
        List<SheetRow> rows = new ArrayList<>();
        EmployeeCostGroup lastG = null;
        for (AdminTeam.Line x : lines) {
            EmployeeCostGroup eg = x.e().effectiveCostGroup();
            if (eg != lastG) {
                lastG = eg;
                rows.add(new SheetRow(eg, null, null));
            }
            rows.add(new SheetRow(null, x, money ? SalaryFundService.dailyRate(x.e(), ym.atDay(1)) : null));
        }
        model.addAttribute("rows", rows);
        model.addAttribute("cols", m.days().size() + 2);
        model.addAttribute("days", m.days());
        model.addAttribute("chips", chips);
        model.addAttribute("ym", ym.toString());
        model.addAttribute("title2", monthTitle(ym));
        model.addAttribute("monthName", MONTH[ym.getMonthValue() - 1]);
        model.addAttribute("prev", ym.minusMonths(1).toString());
        model.addAttribute("next", ym.isBefore(now) ? ym.plusMonths(1).toString() : null);
        model.addAttribute("wd", wd);
        model.addAttribute("passed", ym.equals(now) ? passed : -1);
        model.addAttribute("worked", worked);
        model.addAttribute("workedText", F.dec(worked, worked.stripTrailingZeros().scale() > 0 ? 1 : 0));
        model.addAttribute("absent", absent);
        model.addAttribute("ftText", F.dec(worked, worked.stripTrailingZeros().scale() > 0 ? 1 : 0) + NB
                + F.plural(worked.setScale(0, java.math.RoundingMode.CEILING).intValue(), "смена", "смены", "смен")
                + (absent > 0 ? " · пропусков " + absent : ""));
        model.addAttribute("empty", empty);
        model.addAttribute("pay", pay);
        tabs(model, "sheet");
        page(model, "Сотрудники", "табель: кто пришёл по дням месяца", "staff");
        return "admin/staff/timesheet";
    }

    /** Строка табеля: заголовок группы (head) или сотрудник с его дневной ставкой месяца (rate — для подсчёта на странице). */
    public record SheetRow(EmployeeCostGroup head, AdminTeam.Line line, BigDecimal rate) {
        public String key() {
            return AdminTeam.key(head != null ? head : line.e().effectiveCostGroup());
        }

        public String sub() {
            Employee e = line.e();
            return (e.getPosition() != null ? e.getPosition() : "") + (!e.isActive() ? " · уволен" + AdminTeam.a(e) : "");
        }

        public String rateAttr() {
            return rate == null ? "" : rate.stripTrailingZeros().toPlainString();
        }
    }

    /**
     * Изменённые клетки — поля c_{сотрудник}_{yyyy-mm-dd} = P / H / A / O / пусто; пустые прошедшие дни тех, кто в
     * табеле, добавляются сами — TimesheetService сохранит их как «не пришёл» (как приложение, которое шлёт весь месяц).
     */
    @PostMapping("/admin/staff/timesheet")
    public String saveTimesheet(@RequestParam String month, HttpServletRequest req, RedirectAttributes ra) {
        LocalDate today = LocalDate.now();
        YearMonth ym = parse(month, YearMonth.from(today));
        try {
            Map<String, TimesheetService.CellInput> cells = new LinkedHashMap<>();
            for (Map.Entry<String, String[]> e : req.getParameterMap().entrySet()) {
                if (!e.getKey().startsWith("c_")) continue;
                String[] parts = e.getKey().split("_");
                if (parts.length != 3) continue;
                String v = e.getValue().length == 0 ? "" : e.getValue()[0].trim();
                LocalDate d = LocalDate.parse(parts[2]);
                if (!YearMonth.from(d).equals(ym) || d.isAfter(today)) continue;
                cells.put(parts[1] + "|" + d, new TimesheetService.CellInput(Long.valueOf(parts[1]), d, v.isEmpty() ? null : v));
            }
            int changed = cells.size();
            AdminTeam.Month m = month(ym);
            int miss = 0;
            for (Employee e : employeeRepository.findAll()) {
                if (!AdminTeam.shown(e, m)) continue;
                for (AdminTeam.Cell c : AdminTeam.line(e, m, today, false).cells()) {
                    if (c.miss() && !cells.containsKey(e.getId() + "|" + c.d())) {
                        cells.put(e.getId() + "|" + c.d(), new TimesheetService.CellInput(e.getId(), c.d(), null));
                        miss++;
                    }
                }
            }
            if (cells.isEmpty()) {
                ok(ra, "Изменений нет — табель уже сохранён.");
            } else {
                timesheetService.save(ym, new ArrayList<>(cells.values()), currentUserService.username().orElse("admin"));
                ok(ra, "Табель за " + MONTH[ym.getMonthValue() - 1] + " сохранён: " + F.count(changed + miss, "отметка", "отметки", "отметок")
                        + (miss > 0 ? ", из них «не пришёл» за пустые дни — " + miss : ""));
            }
        } catch (RuntimeException e) {
            fail(ra, e);
        }
        String next = req.getParameter("next");
        return next != null && next.matches("/admin/[\\w/?=&.-]*") ? "redirect:" + next : "redirect:/admin/staff/timesheet?month=" + ym;
    }

    @GetMapping("/admin/staff/timesheet.xlsx")
    public ResponseEntity<byte[]> xlsx(@RequestParam(required = false) String month) {
        YearMonth ym = parse(month, YearMonth.now());
        byte[] bytes = timesheetService.xlsx(ym, money());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("Табель_" + ym + ".xlsx", StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).body(bytes);
    }

    // ------------------------------------------------------------------ входы в CRM

    public record Sec(String title, List<Boolean> sees) {
    }

    @GetMapping("/admin/staff/users")
    @Transactional(readOnly = true)
    public String users(Model model, RedirectAttributes ra) {
        if (!money()) {
            fail(ra, new IllegalStateException("Входы в CRM ведут администратор и бухгалтер"));
            return "redirect:/admin/staff";
        }
        List<User> rows = new ArrayList<>(userService.findAll());
        rows.sort(AdminTeam.USERS);
        Long me = currentUserService.user().map(User::getId).orElse(null);
        List<Sec> matrix = AdminSections.ALL.stream().map(s -> new Sec(s.title(),
                AdminTeam.ROLES.stream().map(s::visibleTo).toList())).toList();
        Map<String, String> sees = new LinkedHashMap<>();
        Map<String, String> roles = new LinkedHashMap<>();
        for (Role r : AdminTeam.ROLES) {
            sees.put(r.name(), r == Role.ADMIN ? "Видит все разделы, настройки и входы." : "Видит: " + AdminTeam.sees(r) + ".");
            roles.put(r.name(), r.getLabel());
        }
        model.addAttribute("rows", rows);
        model.addAttribute("meId", me);
        model.addAttribute("roles", AdminTeam.ROLES);
        model.addAttribute("matrix", matrix);
        Map<String, String> seesBy = new HashMap<>();
        for (Role r : Role.values()) seesBy.put(r.name(), AdminTeam.sees(r));
        model.addAttribute("seesBy", seesBy);
        model.addAttribute("roleShorts", AdminTeam.ROLES.stream().map(AdminTeam::roleShort).toList());
        model.addAttribute("names", employeeRepository.findAll().stream()
                .filter(e -> e.isActive() && e.effectiveCostGroup() != EmployeeCostGroup.BRIGADE).map(Employee::getFullName)
                .sorted(AdminTeam.RU::compare).toList());
        model.addAttribute("usersJson", AdminJson.of(Map.of("sees", sees, "roles", roles, "admin", currentUserService.hasRole(Role.ADMIN),
                "logins", rows.stream().map(User::getUsername).toList())));
        tabs(model, "users");
        page(model, "Сотрудники", "входы в админку и приложение: логин, роль, пароль", "staff");
        return "admin/staff/users";
    }

    @GetMapping({"/admin/staff/users/new", "/admin/staff/users/{id:\\d+}"})
    public String userPage() {
        return "redirect:/admin/staff/users";
    }

    @PostMapping({"/admin/staff/users", "/admin/staff/users/{id:\\d+}"})
    public String saveUser(@PathVariable(required = false) Long id, @RequestParam(defaultValue = "") String name,
                           @RequestParam(defaultValue = "") String login, @RequestParam(required = false) Role role,
                           @RequestParam(defaultValue = "false") boolean on, @RequestParam(defaultValue = "") String password,
                           RedirectAttributes ra) {
        try {
            if (!money()) throw new IllegalStateException("Входы в CRM ведут администратор и бухгалтер");
            List<String> errs = AdminTeam.userErrors(name, login, password, id == null, id, userService.findAll());
            if (!errs.isEmpty()) throw new IllegalArgumentException(String.join(" ", errs));
            String l = login.trim().toLowerCase();
            User me = currentUserService.user().orElse(null);
            boolean self = id != null && me != null && me.getId().equals(id);
            if (id == null) {
                Role r = role == null ? Role.SALES : role;
                if (r == Role.ADMIN && !currentUserService.hasRole(Role.ADMIN)) throw new IllegalStateException("Роль «Администратор» даёт только администратор");
                User u = userService.create(l, password, name.trim(), r);
                if (!on) userService.update(u.getId(), l, name.trim(), r, false, null);
                ok(ra, "Вход «" + l + "» создан — " + r.getLabel().toLowerCase() + " может войти");
            } else {
                User u = userService.findById(id);
                Role r = self || role == null ? u.getRole() : role;
                boolean enabled = self ? u.isEnabled() : on;
                if (r != u.getRole() && (r == Role.ADMIN || u.getRole() == Role.ADMIN) && !currentUserService.hasRole(Role.ADMIN)) {
                    throw new IllegalStateException("Роль «Администратор» даёт и снимает только администратор");
                }
                userService.update(id, l, name.trim(), r, enabled, password.isEmpty() ? null : password);
                ok(ra, "Вход «" + l + "» сохранён" + (password.isEmpty() ? "" : ", пароль изменён"));
            }
        } catch (RuntimeException e) {
            fail(ra, e);
        }
        return "redirect:/admin/staff/users";
    }

    // ------------------------------------------------------------------ свой пароль (любая роль)

    @GetMapping("/admin/me/password")
    public String passwordForm(Model model) {
        model.addAttribute("canUsers", money());
        page(model, "Сменить пароль", "", "");
        return "admin/staff/password";
    }

    @PostMapping("/admin/me/password")
    public String password(@RequestParam(defaultValue = "") String current, @RequestParam(defaultValue = "") String password,
                           @RequestParam(defaultValue = "") String repeat, RedirectAttributes ra) {
        try {
            User me = currentUserService.user().orElseThrow();
            if (current.isEmpty()) throw new IllegalArgumentException("Введите текущий пароль.");
            if (!passwordEncoder.matches(current, me.getPasswordHash())) throw new IllegalArgumentException("Текущий пароль неверный.");
            if (password.length() < 6) throw new IllegalArgumentException("Новый пароль — не короче 6 знаков.");
            if (!password.equals(repeat)) throw new IllegalArgumentException("Пароли не совпадают.");
            userService.update(me.getId(), me.getUsername(), me.getFullName(), me.getRole(), me.isEnabled(), password);
            ok(ra, "Пароль изменён. На других устройствах войдите с новым.");
            return "redirect:/admin";
        } catch (RuntimeException e) {
            fail(ra, e);
            return "redirect:/admin/me/password";
        }
    }
}
