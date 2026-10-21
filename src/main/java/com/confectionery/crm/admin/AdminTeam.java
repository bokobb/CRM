package com.confectionery.crm.admin;

import com.confectionery.crm.domain.*;
import com.confectionery.crm.service.SalaryFundService;

import java.math.BigDecimal;
import java.text.Collator;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/**
 * Сотрудники по макету (часть 4): группы затрат с подсказками, табель месяца по сотруднику — отметка, «по бригаде»,
 * воскресенье, а также дни до приёма и после увольнения, пустые прошедшие дни (при сохранении станут «не пришёл»),
 * последняя смена, проверки карточки сотрудника, роли и разделы для «Входов в CRM».
 * Правила табеля — как в TimesheetService: своя отметка важнее; без неё — «пришёл», если человек стоял в бригаде
 * заявки в этот день, воскресенье — выходной, иначе пусто. Начислено — ставка за смену или оклад ÷ рабочие дни
 * месяца (SalaryFundService.dailyRate) за каждый «пришёл», половина — за «полдня».
 */
public final class AdminTeam {

    private AdminTeam() {
    }

    static final AdminFormat F = new AdminFormat();
    static final String NB = AdminFormat.NB;
    static final Collator RU = Collator.getInstance(Locale.forLanguageTag("ru"));
    static final BigDecimal HALF = new BigDecimal("0.5");

    /** Порядок групп затрат в списке и табеле. */
    public static final List<EmployeeCostGroup> ORDER = List.of(EmployeeCostGroup.BRIGADE, EmployeeCostGroup.PRODUCTION,
            EmployeeCostGroup.SALES, EmployeeCostGroup.ADMIN);

    /** Ключ группы для адреса и стиля (gt--brigade …). */
    public static String key(EmployeeCostGroup g) {
        return switch (g) {
            case BRIGADE -> "brigade";
            case PRODUCTION -> "shop";
            case SALES -> "sales";
            case ADMIN -> "admin";
        };
    }

    public static EmployeeCostGroup group(String key) {
        for (EmployeeCostGroup g : EmployeeCostGroup.values()) if (key(g).equals(key)) return g;
        return null;
    }

    public static String hint(EmployeeCostGroup g) {
        return switch (g) {
            case BRIGADE -> "ставка за смену — в себестоимость заявки, где человек в бригаде";
            case PRODUCTION -> "оклады — накладные цеха: делятся между заявками дня";
            case SALES -> "оклады — расходы на продажи, не в себестоимость";
            case ADMIN -> "оклады — администрация, не в себестоимость";
        };
    }

    /** Группа задана вручную (иначе — по должности). */
    public static boolean manual(Employee e) {
        return e.getCostGroup() != null;
    }

    public static String initials(String name) {
        String[] p = (name == null ? "" : name.trim()).split("\\s+");
        String a = p.length > 0 && !p[0].isEmpty() ? p[0].substring(0, 1) : "";
        String b = p.length > 1 && !p[1].isEmpty() ? p[1].substring(0, 1) : "";
        return (a + b).toUpperCase();
    }

    /** Для «уволен/уволена», «стоял/стояла»: по фамилии (‑ова, ‑ева, ‑ина, ‑ская), иначе по имени на ‑а/‑я. */
    public static boolean fem(Employee e) {
        String[] p = (e.getFullName() == null ? "" : e.getFullName().trim()).split("\\s+");
        String last = p[p.length - 1].toLowerCase(), first = p[0].toLowerCase();
        return last.matches(".*((ов|ев|ёв|ин|ын|ск)ая|(ов|ев|ёв|ин|ын)а)") || first.matches(".*[ая]");
    }

    public static String a(Employee e) {
        return fem(e) ? "а" : "";
    }

    public static String rateText(Employee e) {
        if (e.getDailyRate() != null) return F.money(e.getDailyRate()) + " за смену";
        if (e.getMonthlySalary() != null) return F.money(e.getMonthlySalary()) + " в месяц";
        return "";
    }

    static int sortGroup(Employee e) {
        return ORDER.indexOf(e.effectiveCostGroup());
    }

    public static final Comparator<Employee> BY_NAME = (x, y) -> RU.compare(x.getFullName(), y.getFullName());
    public static final Comparator<Employee> BY_GROUP = Comparator.comparingInt(AdminTeam::sortGroup).thenComparing(BY_NAME);

    // ------------------------------------------------------------------ данные месяца

    /** Отметки и бригады месяца: сотрудник → день → отметка; сотрудник → день → заявки, где он в бригаде. */
    public record Month(YearMonth ym, Map<Long, Map<LocalDate, String>> marks,
                        Map<Long, Map<LocalDate, List<ProductionRequest>>> brig) {

        public static Month of(YearMonth ym, Collection<TimesheetMark> marks, Collection<ProductionBrigadeMember> members) {
            Map<Long, Map<LocalDate, String>> m = new HashMap<>();
            for (TimesheetMark x : marks) {
                if (YearMonth.from(x.getDate()).equals(ym)) m.computeIfAbsent(x.getEmployee().getId(), k -> new HashMap<>()).put(x.getDate(), x.getStatus());
            }
            Map<Long, Map<LocalDate, List<ProductionRequest>>> b = new HashMap<>();
            for (ProductionBrigadeMember x : members) {
                ProductionRequest r = x.getRequest();
                if (r.getStatus() == ProductionRequestStatus.CANCELLED || !YearMonth.from(r.getDocumentDate()).equals(ym)) continue;
                b.computeIfAbsent(x.getEmployee().getId(), k -> new HashMap<>())
                        .computeIfAbsent(r.getDocumentDate(), k -> new ArrayList<>()).add(r);
            }
            return new Month(ym, m, b);
        }

        public List<LocalDate> days() {
            List<LocalDate> out = new ArrayList<>();
            for (int d = 1; d <= ym.lengthOfMonth(); d++) out.add(ym.atDay(d));
            return out;
        }

        /** Сколько человек стоит в бригадах дня. */
        public int inBrigades(LocalDate d) {
            int n = 0;
            for (Map<LocalDate, List<ProductionRequest>> x : brig.values()) if (x.containsKey(d)) n++;
            return n;
        }

        public boolean any(Employee e) {
            return marks.containsKey(e.getId()) || brig.containsKey(e.getId());
        }
    }

    /**
     * День табеля. s — P (пришёл), H (полдня), A (не пришёл), O (выходной), null — пусто; auto — не отметка, а по бригаде
     * или воскресенье; pre/gone — до приёма или после увольнения (не отмечается); miss — пустой прошедший день, при
     * сохранении станет «не пришёл».
     */
    public record Cell(LocalDate d, String s, boolean auto, boolean fut, boolean pre, boolean gone, boolean miss, boolean today) {

        public boolean sun() {
            return d.getDayOfWeek() == DayOfWeek.SUNDAY;
        }

        public boolean locked() {
            return fut || pre || gone;
        }

        /** Вне работы — до приёма или после увольнения, и своей отметки нет. */
        public boolean out() {
            return (pre || gone) && (s == null || auto);
        }

        public String sym() {
            return sym(s);
        }

        public String code() {
            return s == null ? "" : s;
        }

        /** Отметка в календаре карточки: без подсветки пустых дней. */
        public String calCls() {
            return "cm cm--" + (s == null ? "E" : s) + (auto ? " is-auto" : "");
        }

        public int day() {
            return d.getDayOfMonth();
        }

        public String wd() {
            return F.wday(d);
        }

        public String cls() {
            return "cm cm--" + (s == null ? "E" : s) + (auto ? " is-auto" : "") + (miss ? " is-miss" : "");
        }

        public String title() {
            if (out()) return F.dml(d) + ": " + (pre ? "ещё не работал" : "уже не работал");
            return F.dml(d) + ": " + name(s) + (auto && "P".equals(s) ? " (по бригаде)" : "");
        }

        public static String sym(String s) {
            if (s == null) return "·";
            return switch (s) {
                case "P" -> "✓";
                case "H" -> "½";
                case "A" -> "✗";
                case "O" -> "В";
                default -> "·";
            };
        }

        public static String name(String s) {
            if (s == null) return "пусто";
            return switch (s) {
                case "P" -> "пришёл";
                case "H" -> "полдня";
                case "A" -> "не пришёл";
                case "O" -> "выходной";
                default -> "пусто";
            };
        }
    }

    /** Строка табеля: дни, отработано (полдня = 0,5), пропуски, начислено (null — роль денег не видит), пустых дней. */
    public record Line(Employee e, List<Cell> cells, BigDecimal worked, int absent, BigDecimal pay, int empty) {

        public String workedText() {
            return worked.signum() == 0 ? "0" : F.dec(worked, worked.stripTrailingZeros().scale() > 0 ? 1 : 0);
        }

        public String group() {
            return key(e.effectiveCostGroup());
        }

        /** «9 смен», «22,5 смены», «смен нет». */
        public String shiftsText() {
            if (worked.signum() == 0) return "смен нет";
            int n = worked.setScale(0, java.math.RoundingMode.CEILING).intValue();
            return workedText() + NB + F.plural(n, "смена", "смены", "смен");
        }

        public int half() {
            return (int) cells.stream().filter(c -> "H".equals(c.s())).count();
        }

        public LocalDate firstMiss() {
            return cells.stream().filter(Cell::miss).map(Cell::d).findFirst().orElse(null);
        }
    }

    public static Line line(Employee e, Month m, LocalDate today, boolean money) {
        Map<LocalDate, String> own = m.marks().getOrDefault(e.getId(), Map.of());
        Map<LocalDate, List<ProductionRequest>> brig = m.brig().getOrDefault(e.getId(), Map.of());
        List<Cell> cells = new ArrayList<>();
        BigDecimal worked = BigDecimal.ZERO, pay = money ? BigDecimal.ZERO : null;
        int absent = 0, empty = 0;
        for (LocalDate d : m.days()) {
            String s = own.get(d);
            boolean auto = false;
            if (s == null) {
                if (brig.containsKey(d)) {
                    s = "P";
                    auto = true;
                } else if (d.getDayOfWeek() == DayOfWeek.SUNDAY) {
                    s = "O";
                    auto = true;
                }
            }
            boolean fut = d.isAfter(today);
            if (fut && auto && "P".equals(s)) {            // бригада на завтра — ещё не смена
                s = null;
                auto = false;
            }
            boolean pre = e.getHiredAt() != null && d.isBefore(e.getHiredAt());
            boolean gone = e.getFiredAt() != null && d.isAfter(e.getFiredAt());
            boolean miss = !fut && s == null && d.isBefore(today) && !pre && !gone;
            if (miss) empty++;
            BigDecimal share = "P".equals(s) ? BigDecimal.ONE : "H".equals(s) ? HALF : null;
            if (share != null) {
                worked = worked.add(share);
                BigDecimal r = SalaryFundService.dailyRate(e, d);
                if (money && r != null) pay = pay.add(r.multiply(share));
            } else if ("A".equals(s)) {
                absent++;
            }
            cells.add(new Cell(d, s, auto, fut, pre, gone, miss, d.equals(today)));
        }
        return new Line(e, cells, worked, absent, pay, empty);
    }

    /** В табеле месяца: работающие (принятые не позже конца месяца) и уволенные, у кого есть отметки или бригады за месяц. */
    public static boolean shown(Employee e, Month m) {
        if (e.getHiredAt() != null && e.getHiredAt().isAfter(m.ym().atEndOfMonth())) return false;
        if (e.getFiredAt() != null && e.getFiredAt().isBefore(m.ym().atDay(1)) && !m.any(e)) return false;
        return e.isActive() || m.any(e);
    }

    /** Месяц, который показывает карточка: текущий, а у уволенного до него — месяц увольнения. */
    public static YearMonth baseMonth(Employee e, LocalDate today) {
        YearMonth now = YearMonth.from(today);
        if (!e.isActive() && e.getFiredAt() != null && YearMonth.from(e.getFiredAt()).isBefore(now)) return YearMonth.from(e.getFiredAt());
        return now;
    }

    // ------------------------------------------------------------------ последняя смена

    /** Последняя смена: по бригадам заявок (r — заявка), а нет их — по табелю (r = null). */
    public record Last(LocalDate d, ProductionRequest r) {
    }

    public static Map<Long, Last> lasts(Collection<ProductionBrigadeMember> members, Collection<TimesheetMark> marks, LocalDate today) {
        Map<Long, Last> out = new HashMap<>();
        for (ProductionBrigadeMember x : members) {
            ProductionRequest r = x.getRequest();
            if (r.getStatus() == ProductionRequestStatus.CANCELLED || r.getDocumentDate().isAfter(today)) continue;
            Last cur = out.get(x.getEmployee().getId());
            if (cur == null || cur.r() == null || r.getDocumentDate().isAfter(cur.d())
                    || (r.getDocumentDate().equals(cur.d()) && r.getId() > cur.r().getId())) {
                out.put(x.getEmployee().getId(), new Last(r.getDocumentDate(), r));
            }
        }
        for (TimesheetMark x : marks) {
            if (!"P".equals(x.getStatus()) && !"H".equals(x.getStatus()) || x.getDate().isAfter(today)) continue;
            Long id = x.getEmployee().getId();
            Last cur = out.get(id);
            if (cur == null || (cur.r() == null && x.getDate().isAfter(cur.d()))) out.put(id, new Last(x.getDate(), null));
        }
        return out;
    }

    // ------------------------------------------------------------------ карточка: проверки

    /** Поля формы сотрудника — как ввели (строки), чтобы при ошибке ничего не стиралось. */
    public record Form(String name, String pos, String phone, String grp, String rate, String sal, boolean on) {

        public static Form of(Employee e) {
            return new Form(e.getFullName() == null ? "" : e.getFullName(), e.getPosition() == null ? "" : e.getPosition(),
                    e.getPhone() == null ? "" : e.getPhone(), e.getCostGroup() == null ? "" : key(e.getCostGroup()),
                    plain(e.getDailyRate()), plain(e.getMonthlySalary()), e.getId() == null || e.isActive());
        }

        public static Form of(Map<String, String> f) {
            return new Form(t(f.get("name")), t(f.get("pos")), t(f.get("phone")), t(f.get("grp")),
                    digits(f.get("rate")), digits(f.get("sal")), f.containsKey("on") ? "true".equals(f.get("on")) : true);
        }

        public EmployeeCostGroup group() {
            return grp.isEmpty() ? null : AdminTeam.group(grp);
        }

        public EmployeeCostGroup effective() {
            EmployeeCostGroup g = group();
            return g != null ? g : EmployeeCostGroup.byPosition(pos);
        }

        public BigDecimal rateNum() {
            return rate.isEmpty() ? null : new BigDecimal(rate);
        }

        public BigDecimal salNum() {
            return sal.isEmpty() ? null : new BigDecimal(sal);
        }

        private static String t(String s) {
            return s == null ? "" : s.trim();
        }

        private static String digits(String s) {
            return s == null ? "" : s.replaceAll("\\D", "");
        }

        private static String plain(BigDecimal v) {
            return v == null ? "" : v.stripTrailingZeros().toPlainString();
        }
    }

    /** Превью формы: как пойдут смены и оклад (html: <b>, «не задана» красным). */
    public static String how(Form f, int workDays, String monthGen) {
        EmployeeCostGroup g = f.effective();
        BigDecimal rate = f.rate().isEmpty() ? null : f.rateNum(), sal = f.sal().isEmpty() ? null : f.salNum();
        boolean hasRate = rate != null && rate.signum() > 0, hasSal = sal != null && sal.signum() > 0;
        BigDecimal perDay = hasSal ? sal.divide(BigDecimal.valueOf(workDays), 2, java.math.RoundingMode.HALF_UP) : null;
        if (g == EmployeeCostGroup.BRIGADE) {
            if (hasRate) return "Смена в бригаде заявки — <b>" + F.money(rate) + "</b> в её себестоимость.";
            if (hasSal) return "Смена в бригаде заявки — <b>" + F.money(perDay) + "</b> в её себестоимость (оклад " + F.money(sal)
                    + " ÷ " + workDays + " рабочих дней " + monthGen + ").";
            return "<span class=\"pay-bad\">Без ставки и оклада</span> смены не попадут ни в себестоимость, ни в начисления табеля.";
        }
        if (hasSal) return "Оклад " + F.money(sal) + " ÷ " + workDays + " рабочих дней " + monthGen + " = <b>" + F.money(perDay) + "</b> в день — "
                + (g == EmployeeCostGroup.PRODUCTION ? "в накладные цеха" : "в расходы, не в себестоимость") + ".";
        if (hasRate) return "Ставка " + F.money(rate) + " за отработанный день по табелю.";
        return "Оклад не задан — в начислениях табеля будет 0.";
    }

    static String normName(String s) {
        return AdminRequests.norm(s == null ? "" : s).replaceAll("\\s+", " ").trim();
    }

    /** Ошибки формы — поле → текст, как в макете. */
    public static Map<String, String> errors(Form f, Long selfId, Collection<Employee> all, boolean money) {
        Map<String, String> x = new LinkedHashMap<>();
        String nm = normName(f.name());
        if (nm.isEmpty()) x.put("name", "Введите ФИО.");
        else if (nm.split(" ").length < 2) x.put("name", "Фамилия и имя — через пробел, как в 1С.");
        else if (all.stream().anyMatch(y -> !Objects.equals(y.getId(), selfId) && normName(y.getFullName()).equals(nm)))
            x.put("name", "Такой сотрудник уже есть — откройте его карточку.");
        if (!f.phone().isEmpty() && f.phone().replaceAll("\\D", "").length() != 11) x.put("phone", "Телефон — 11 цифр, например +7 700 000 00 00.");
        if (money) {
            if (!f.rate().isEmpty() && !(f.rateNum().signum() > 0 && f.rateNum().compareTo(BigDecimal.valueOf(200_000)) <= 0))
                x.put("rate", "Ставка — от 1 до 200 000 ₸.");
            if (!f.sal().isEmpty() && !(f.salNum().signum() > 0 && f.salNum().compareTo(BigDecimal.valueOf(5_000_000)) <= 0))
                x.put("sal", "Оклад — от 1 до 5 000 000 ₸.");
        }
        return x;
    }

    // ------------------------------------------------------------------ входы в CRM

    /** Роли в порядке столбцов таблицы «Что видит каждая роль» и короткие подписи. */
    public static final List<Role> ROLES = List.of(Role.ADMIN, Role.ACCOUNTANT, Role.SALES, Role.WAREHOUSE_FG,
            Role.WAREHOUSE_RAW, Role.TECHNOLOGIST);

    public static String roleShort(Role r) {
        return switch (r) {
            case ADMIN -> "Админ";
            case ACCOUNTANT -> "Бухгалтер";
            case SALES -> "Продажи";
            case WAREHOUSE_FG -> "Склад ГП";
            case WAREHOUSE_RAW -> "Склад сырья";
            case TECHNOLOGIST -> "Технолог";
        };
    }

    /** Разделы меню, которые видит роль, — подписи через запятую. */
    public static String sees(Role r) {
        if (r == Role.ADMIN) return "все разделы, настройки и входы";
        return String.join(", ", AdminSections.ALL.stream().filter(s -> s.visibleTo(r)).map(AdminSections.Section::title).toList());
    }

    public static final Comparator<User> USERS = Comparator.comparing((User u) -> !u.isEnabled())
            .thenComparingInt(u -> ROLES.indexOf(u.getRole()))
            .thenComparing((x, y) -> RU.compare(x.getFullName(), y.getFullName()));

    /** Ошибки входа: логин — латиница, цифры, точка, дефис; пароль — от 6 знаков (у старого входа — если меняют). */
    public static List<String> userErrors(String name, String login, String password, boolean isNew, Long selfId, Collection<User> all) {
        List<String> x = new ArrayList<>();
        if (name == null || name.isBlank()) x.add("Введите ФИО.");
        String l = login == null ? "" : login.trim().toLowerCase();
        if (!l.matches("[a-z0-9._-]{3,40}")) x.add("Логин — от 3 знаков: латиница, цифры, точка, дефис.");
        else if (all.stream().anyMatch(u -> !Objects.equals(u.getId(), selfId) && u.getUsername().equalsIgnoreCase(l))) x.add("Такой логин уже есть.");
        String p = password == null ? "" : password;
        if (isNew && p.length() < 6) x.add("Пароль — не короче 6 знаков.");
        if (!isNew && !p.isEmpty() && p.length() < 6) x.add("Новый пароль — не короче 6 знаков.");
        return x;
    }
}
