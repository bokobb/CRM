package com.confectionery.crm.admin;

import com.confectionery.crm.api.dto.ProductionCostDto;
import com.confectionery.crm.domain.*;
import com.confectionery.crm.service.ProductionRequestService;
import com.confectionery.crm.service.SalaryFundService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/**
 * «Производство» по макету (часть 3): плитки дня, график себестоимости за 14 дней, заявки дня с бригадой и
 * себестоимостью изделий, окно «Бригада», отчёт за день; страница себестоимости заявки — за единицу, рецептура
 * «норма и выдано» с ценами покупок, бригада, накладные цеха, оприходовано. Цифры — ProductionCostService
 * (как в приложении), здесь только вид: проценты, подписи, кто в какой бригаде.
 */
public final class AdminProduction {

    private AdminProduction() {
    }

    static final AdminFormat F = new AdminFormat();
    static final String NB = AdminFormat.NB;

    static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /** +61 % · −2,5 % (digits — знаков после запятой). */
    public static String pct(BigDecimal v, int digits) {
        if (v == null) return "—";
        String s = F.dec(v.abs(), digits);
        return (v.signum() > 0 ? "+" : v.signum() < 0 ? "−" : "") + s + NB + "%";
    }

    /** Наценка к себестоимости: цена / себестоимость − 1, в процентах. */
    public static BigDecimal markup(BigDecimal sale, BigDecimal cost) {
        if (sale == null || cost == null || cost.signum() <= 0) return null;
        return sale.divide(cost, 6, RoundingMode.HALF_UP).subtract(BigDecimal.ONE).multiply(BigDecimal.valueOf(100));
    }

    /** −3 000 ₸ / +3 000 ₸ со знаком (для разницы с нормой). */
    public static String signedMoney(BigDecimal v) {
        if (v == null) return "—";
        return (v.signum() > 0 ? "+" : v.signum() < 0 ? "−" : "") + F.money(v.abs());
    }

    public static boolean extra(ProductionCostDto.Line l) {
        return nz(l.batches()).signum() == 0 && nz(l.plannedQuantity()).signum() == 0;
    }

    public static boolean sunday(LocalDate d) {
        return d.getDayOfWeek() == DayOfWeek.SUNDAY;
    }

    // ================================================================== день

    public record Tile(String cls, String icon, String label, String value, String unit, String sub) {
    }

    /** Заявка дня: шапка карточки и строки изделий. sub/names — готовый HTML (числа жирным). */
    public record Card(ProductionCostDto.Request r, ProductionRequest req, String key, AdminRequests.Badge badge, String sub,
                       BigDecimal labor, List<CardLine> lines) {
        public boolean fresh() {
            return key.equals("new");
        }
    }

    public record CardLine(ProductionCostDto.Line l, String photo, boolean extra, BigDecimal markup, boolean showPlan) {
    }

    public static String costLine(ProductionCostDto.Request r, boolean fresh, boolean labor) {
        BigDecimal dev = nz(r.issuedCost()).subtract(nz(r.normCost()));
        BigDecimal cost = nz(r.issuedCost()).add(labor ? nz(r.laborCost()).add(nz(r.overheadCost())) : BigDecimal.ZERO);
        return "сырьё " + (fresh ? "по составу " : "") + "<b>" + F.money(r.issuedCost()) + "</b> · норма " + F.money(r.normCost())
                + (dev.abs().compareTo(BigDecimal.ONE) >= 0 ? " <b class=\"" + (dev.signum() > 0 ? "pay-bad" : "ok-t") + "\">" + signedMoney(dev) + "</b>" : "")
                + " · себестоимость <b>" + F.money(cost) + "</b>";
    }

    public static Card card(ProductionCostDto.Request r, ProductionRequest req, Map<Long, Product> products, boolean labor) {
        String key = req == null ? "new" : AdminRequests.key(req);
        List<CardLine> lines = new ArrayList<>();
        for (ProductionCostDto.Line l : r.lines()) {
            Product p = products.get(l.productId());
            BigDecimal mk = l.semiFinished() ? null : markup(l.salePrice(), l.unitCost());
            boolean plan = l.received() && l.unitCost() != null && l.planUnitCost() != null
                    && l.unitCost().subtract(l.planUnitCost()).abs().compareTo(new BigDecimal("0.5")) >= 0;
            lines.add(new CardLine(l, p == null ? null : p.getImageUrl(), extra(l), mk, plan));
        }
        return new Card(r, req, key, AdminRequests.badge(key), costLine(r, key.equals("new"), labor), r.laborCost(), lines);
    }

    /** Плитки дня, как pdTiles() макета. Технолог (labor=false) видит сырьё и бригады без денег. */
    public static List<Tile> tiles(ProductionCostDto d, List<Card> cards, boolean labor) {
        LocalDate day = d.date();
        if (cards.isEmpty()) {
            String why = sunday(day) ? "воскресенье — цех не работает" : "в этот день заявок не было";
            return List.of(new Tile("", "production", "Себестоимость выпуска", "—", null, why),
                    new Tile("", "reports", "По цене продажи", "—", null, ""),
                    new Tile("", "materials", "Сырьё: выдано / норма", "—", null, ""),
                    labor ? new Tile("", "clients", "Зарплата и накладные", "—", null, "оклады цеха делятся только по заявкам дня")
                            : new Tile("", "clients", "Бригады", "—", null, ""));
        }
        ProductionCostDto.Totals t = d.totals();
        BigDecimal fg = nz(t.finishedCost()), sale = nz(t.finishedSaleValue()), issued = nz(t.issuedCost()), norm = nz(t.normCost());
        BigDecimal units = BigDecimal.ZERO;
        int notGot = 0;
        for (Card c : cards) {
            for (ProductionCostDto.Line l : c.r().lines()) {
                if (l.semiFinished()) continue;
                units = units.add(nz(l.quantity()));
                if (!l.received()) notGot++;
            }
        }
        long fresh = cards.stream().filter(Card::fresh).count();
        long lack = cards.stream().filter(c -> !c.fresh() && c.r().brigade().isEmpty()).count();
        long noBrig = cards.stream().filter(c -> c.r().brigade().isEmpty()).count();
        Set<Long> people = new HashSet<>();
        cards.forEach(c -> c.r().brigade().forEach(m -> people.add(m.employeeId())));
        BigDecimal mk = markup(sale, fg);
        BigDecimal dev = issued.subtract(norm);
        BigDecimal devP = norm.signum() > 0 ? dev.multiply(BigDecimal.valueOf(100)).divide(norm, 4, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        BigDecimal semi = nz(t.semiFinishedCost());

        String s1 = labor ? "сырьё " + F.money(issued) + " · зарплата " + F.money(t.laborCost()) + " · накладные " + F.money(t.overheadCost())
                : "сырьё по выданному — зарплату и накладные видят администратор и бухгалтер";
        if (semi.signum() > 0) s1 += " · <b>" + F.money(semi) + "</b> — полуфабрикаты, войдут в товар при упаковке";
        String s2 = (mk != null ? "наценка <b>" + pct(mk, 0) + "</b> · " : "") + F.num(units) + NB + "шт"
                + (notGot > 0 ? " · " + F.count(notGot, "позиция", "позиции", "позиций") + " ещё по плану" : " · всё оприходовано");
        String s3 = "норма " + F.money(norm) + " · " + (dev.abs().compareTo(BigDecimal.ONE) < 0 ? "точно по норме"
                : "<b class=\"" + (dev.signum() > 0 ? "pay-bad" : "ok-t") + "\">" + signedMoney(dev) + " (" + pct(devP, 1) + ")</b> к норме")
                + (fresh > 0 ? " · у новых — по составу" : "");
        String lackText = lack > 0 ? "<b>без бригады: " + F.count(lack, "заявка", "заявки", "заявок") + "</b>"
                : fresh > 0 && noBrig > 0 ? "у новых заявок бригады ещё нет" : "";
        List<Tile> out = new ArrayList<>();
        out.add(new Tile("", "production", "Себестоимость выпуска", F.dec(fg, 0), "₸", s1));
        out.add(new Tile("", "reports", "По цене продажи", F.dec(sale, 0), "₸", s2));
        out.add(new Tile(devP.compareTo(new BigDecimal("0.5")) > 0 ? "kpi--warm" : "", "materials", "Сырьё: выдано / норма", F.dec(issued, 0), "₸", s3));
        if (labor) {
            BigDecimal lab = nz(t.laborCost()), over = nz(t.overheadCost());
            out.add(new Tile(lack > 0 ? "kpi--warm" : "", "clients", "Зарплата и накладные", F.dec(lab.add(over), 0), "₸",
                    "бригады: " + people.size() + NB + "чел. · " + F.money(lab) + " · накладные цеха " + F.money(over)
                            + (lackText.isEmpty() ? "" : " · " + lackText)));
        } else {
            out.add(new Tile(lack > 0 ? "kpi--warm" : "", "clients", "Бригады", String.valueOf(people.size()), "чел.",
                    lackText.isEmpty() ? "у всех заявок есть бригада" : lackText));
        }
        return out;
    }

    /** Как делится фонд цеха между заявками дня (ProductionCostService.overheadByRequest). */
    public static String overheadMode(List<ProductionRequest> dayRequests) {
        BigDecimal labor = BigDecimal.ZERO;
        for (ProductionRequest r : dayRequests) {
            for (ProductionBrigadeMember m : r.getBrigade()) labor = labor.add(nz(SalaryFundService.dailyRate(m.getEmployee(), r.getDocumentDate())));
        }
        if (labor.signum() > 0) return "пропорционально зарплате бригад";
        boolean issued = dayRequests.stream().anyMatch(r -> !r.getItems().isEmpty());
        return issued ? "пропорционально выданному сырью — бригад нет" : "поровну";
    }

    /** График и таблица за 14 дней: day, себестоимость, по цене продажи, норма, выдано, заявок. */
    public static Map<String, Object> chartData(ProductionCostDto d, Map<LocalDate, Long> counts, LocalDate today) {
        List<Map<String, Object>> days = new ArrayList<>();
        for (ProductionCostDto.Day x : d.history()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("d", x.date().toString());
            m.put("fg", x.finishedCost());
            m.put("sale", x.finishedSaleValue());
            m.put("norm", x.normCost());
            m.put("issued", x.issuedCost());
            m.put("n", counts.getOrDefault(x.date(), 0L));
            days.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", days);
        out.put("sel", d.date().toString());
        out.put("today", today.toString());
        return out;
    }

    /** Строка таблицы «Себестоимость по дням» (свежие сверху). */
    public record HistRow(ProductionCostDto.Day h, long n, BigDecimal markup, BigDecimal dev, boolean sel, boolean today) {
    }

    public static List<HistRow> histRows(ProductionCostDto d, Map<LocalDate, Long> counts, LocalDate today) {
        List<HistRow> out = new ArrayList<>();
        for (ProductionCostDto.Day x : d.history()) {
            out.add(0, new HistRow(x, counts.getOrDefault(x.date(), 0L), markup(x.finishedSaleValue(), x.finishedCost()),
                    nz(x.issuedCost()).subtract(nz(x.normCost())), x.date().equals(d.date()), x.date().equals(today)));
        }
        return out;
    }

    // ================================================================== бригада

    /**
     * Для окна «Бригада»: сотрудники (кто в бригаде по группе затрат — сверху), у кого ставка — только если видна
     * зарплата; по заявкам дня — кто уже выбран и кто занят в другой заявке этого дня.
     */
    public static Map<String, Object> brigadeData(LocalDate day, List<Employee> employees, List<ProductionRequest> dayRequests, boolean labor) {
        List<Map<String, Object>> emps = new ArrayList<>();
        Set<Long> inDay = new HashSet<>();
        dayRequests.forEach(r -> r.getBrigade().forEach(m -> inDay.add(m.getEmployee().getId())));
        employees.stream().filter(e -> e.isActive() || inDay.contains(e.getId()))
                .sorted(Comparator.comparing(Employee::getFullName, String.CASE_INSENSITIVE_ORDER)).forEach(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", e.getId());
                    m.put("name", e.getFullName());
                    m.put("pos", e.getPosition() == null ? "" : e.getPosition());
                    m.put("brig", e.effectiveCostGroup() == EmployeeCostGroup.BRIGADE);
                    m.put("off", !e.isActive());
                    m.put("rate", labor ? SalaryFundService.dailyRate(e, day) : null);
                    emps.add(m);
                });
        Map<String, Object> reqs = new LinkedHashMap<>();
        for (ProductionRequest r : dayRequests) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("title", r.getTitle());
            m.put("sel", r.getBrigade().stream().map(x -> x.getEmployee().getId()).toList());
            Map<String, Long> busy = new LinkedHashMap<>();
            for (ProductionRequest o : dayRequests) {
                if (o == r) continue;
                o.getBrigade().forEach(x -> busy.put(String.valueOf(x.getEmployee().getId()), o.getId()));
            }
            m.put("busy", busy);
            reqs.put(String.valueOf(r.getId()), m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("day", F.dml(day));
        out.put("emps", emps);
        out.put("reqs", reqs);
        out.put("labor", labor);
        return out;
    }

    // ================================================================== отчёт за день

    public static Map<String, Object> reportData(LocalDate day, List<ProductionRequestService.ReportLine> lines) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ProductionRequestService.ReportLine l : lines) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", l.productName());
            m.put("unit", l.unit());
            m.put("b", l.batches());
            m.put("norm", l.norm());
            m.put("fact", l.received());
            m.put("dev", l.deviation());
            m.put("n", l.receivedCount());
            rows.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", day.toString());
        out.put("rows", rows);
        return out;
    }

    // ================================================================== себестоимость заявки

    /** Покупка, по которой взята цена сырья на дату (как IngredientPriceService: последняя не позже, иначе ближайшая после). */
    public record PriceRef(Long purchaseId, LocalDate date, boolean after) {
    }

    public static Map<Long, PriceRef> priceRefs(List<PurchaseItem> pricedNewestFirst, LocalDate asOf) {
        Map<Long, PurchaseItem> before = new HashMap<>(), after = new HashMap<>();
        for (PurchaseItem pi : pricedNewestFirst) {
            Long id = pi.getIngredient().getId();
            if (!pi.getPurchase().getPurchaseDate().isAfter(asOf)) before.putIfAbsent(id, pi);
            else after.put(id, pi);
        }
        Map<Long, PriceRef> out = new HashMap<>();
        Set<Long> ids = new HashSet<>(before.keySet());
        ids.addAll(after.keySet());
        for (Long id : ids) {
            PurchaseItem pi = before.getOrDefault(id, after.get(id));
            out.put(id, new PriceRef(pi.getPurchase().getId(), pi.getPurchase().getPurchaseDate(), pi.getPurchase().getPurchaseDate().isAfter(asOf)));
        }
        return out;
    }

    /** Строка «Рецептура: норма и выдано»: замена склеена с тем, вместо чего выдали; добавленное и убранное подписаны. */
    public record RawRow(ProductionCostDto.Ingredient i, BigDecimal norm, BigDecimal normCost, String unit, String note, boolean quiet,
                         boolean more, boolean free, PriceRef ref) {
        public BigDecimal cost() {
            return nz(i.issuedCost());
        }

        /** «по норме …» под суммой — когда норма и выдано расходятся хотя бы на тенге. */
        public boolean showNormCost() {
            return normCost != null && normCost.subtract(cost()).abs().compareTo(BigDecimal.ONE) >= 0;
        }
    }

    public static List<RawRow> rawRows(List<ProductionCostDto.Ingredient> ings, Map<Long, PriceRef> refs) {
        Map<String, ProductionCostDto.Ingredient> byName = new HashMap<>();
        ings.forEach(i -> byName.put(AdminRequests.norm(i.name()), i));
        Set<Long> merged = new HashSet<>();
        for (ProductionCostDto.Ingredient i : ings) {
            if (i.originalName() == null) continue;
            ProductionCostDto.Ingredient was = byName.get(AdminRequests.norm(i.originalName()));
            if (was != null && was != i && nz(was.issuedQuantity()).signum() == 0) merged.add(was.ingredientId());
        }
        List<RawRow> out = new ArrayList<>();
        for (ProductionCostDto.Ingredient i : ings) {
            if (merged.contains(i.ingredientId())) continue;
            BigDecimal norm = nz(i.normQuantity()), normCost = i.normCost();
            String unit = i.unit(), note = null;
            boolean quiet = false;
            if (i.originalName() != null) {
                ProductionCostDto.Ingredient was = byName.get(AdminRequests.norm(i.originalName()));
                if (was != null && merged.contains(was.ingredientId())) {
                    norm = norm.add(nz(was.normQuantity()));
                    normCost = was.normCost() == null && normCost == null ? null : nz(normCost).add(nz(was.normCost()));
                    if (nz(i.normQuantity()).signum() == 0) unit = was.unit();
                }
                note = "вместо: " + i.originalName();
            } else if (norm.signum() == 0 && nz(i.issuedQuantity()).signum() > 0) {
                note = "добавлено — нет в рецептуре";
            } else if (norm.signum() > 0 && nz(i.issuedQuantity()).signum() == 0) {
                note = "убрано из заявки";
                quiet = true;
            }
            boolean more = norm.signum() > 0 && nz(i.issuedQuantity()).subtract(norm).compareTo(new BigDecimal("0.000001")) > 0;
            out.add(new RawRow(i, norm.signum() == 0 && note != null && note.startsWith("добавлено") ? null : norm, normCost,
                    unit, note, quiet, more, AdminRequests.untracked(i.name()), refs.get(i.ingredientId())));
        }
        return out;
    }

    /** Себестоимость за единицу: план — норма + зарплата + накладные на план, факт — на оприходованное. */
    public record UnitRow(ProductionCostDto.Line l, String photo, boolean extra, BigDecimal diff, BigDecimal diffP, BigDecimal markup) {
    }

    public static List<UnitRow> unitRows(ProductionCostDto.Request r, Map<Long, Product> products) {
        List<UnitRow> out = new ArrayList<>();
        for (ProductionCostDto.Line l : r.lines()) {
            Product p = products.get(l.productId());
            BigDecimal diff = l.unitCost() != null && l.planUnitCost() != null ? l.unitCost().subtract(l.planUnitCost()) : null;
            BigDecimal diffP = diff != null && l.planUnitCost().signum() > 0
                    ? diff.multiply(BigDecimal.valueOf(100)).divide(l.planUnitCost(), 4, RoundingMode.HALF_UP) : null;
            out.add(new UnitRow(l, p == null ? null : p.getImageUrl(), extra(l), diff, diffP,
                    l.semiFinished() ? null : markup(l.salePrice(), l.unitCost())));
        }
        return out;
    }

    /** «Оприходовано»: изделие, замесы и план, факт и отклонение от плана. */
    public record Got(String name, String sub, String fact, String diff, String diffCls) {
    }

    public static List<Got> got(ProductionRequest req) {
        List<Got> out = new ArrayList<>();
        for (ProductionRequestLine l : req.getLines()) {
            boolean ex = AdminRequests.extra(l);
            BigDecimal plan = nz(l.getPlannedQuantity()), fact = l.getReceivedQuantity();
            String diff = null, cls = null;
            if (fact != null && !ex && plan.signum() > 0 && fact.compareTo(plan) != 0) {
                BigDecimal d = fact.subtract(plan);
                diff = (d.signum() > 0 ? "+" : "−") + F.num(d.abs()) + " · " + pct(d.multiply(BigDecimal.valueOf(100)).divide(plan, 4, RoundingMode.HALF_UP), 1);
                cls = d.signum() < 0 ? "pay-bad" : "ok-t";
            }
            out.add(new Got(AdminRequests.shortName(l.getProduct().getName()),
                    ex ? "сверх заявки" : AdminRequests.qn(l.getBatches()) + NB + "зам. · план " + F.num(plan) + NB + "шт",
                    fact == null ? null : F.num(fact) + NB + "шт", diff, cls));
        }
        return out;
    }

    /** Бригада заявки на её день: кто, должность, ставка (оклад ÷ рабочие дни — подписано). */
    public record Member(Long id, String name, String position, BigDecimal rate, String rateNote) {
    }

    public static List<Member> members(ProductionRequest req, boolean labor) {
        int wd = SalaryFundService.workingDays(YearMonth.from(req.getDocumentDate()));
        return req.getBrigade().stream().map(ProductionBrigadeMember::getEmployee)
                .sorted(Comparator.comparing(Employee::getFullName, String.CASE_INSENSITIVE_ORDER))
                .map(e -> new Member(e.getId(), e.getFullName(), e.getPosition(),
                        labor ? SalaryFundService.dailyRate(e, req.getDocumentDate()) : null,
                        labor && e.getDailyRate() == null && e.getMonthlySalary() != null
                                ? "оклад " + F.money(e.getMonthlySalary()) + " ÷ " + wd + NB + "раб. дн." : null))
                .toList();
    }

    /** «Оклады группы «Цех» — технолог, слесарь… — 1 500 000 ₸ в месяц ÷ 27 рабочих дней». */
    public static String overheadHint(List<Employee> employees, LocalDate day, String mode) {
        List<Employee> shop = employees.stream().filter(e -> e.isActive() && e.getMonthlySalary() != null
                && e.effectiveCostGroup() == EmployeeCostGroup.PRODUCTION).toList();
        if (shop.isEmpty()) return "В группе «Цех» нет сотрудников с окладом — накладных цеха нет.";
        Set<String> pos = new LinkedHashSet<>();
        shop.forEach(e -> pos.add(e.getPosition() == null || e.getPosition().isBlank() ? e.getFullName() : e.getPosition().trim().toLowerCase(Locale.ROOT)));
        BigDecimal sum = shop.stream().map(Employee::getMonthlySalary).reduce(BigDecimal.ZERO, BigDecimal::add);
        int wd = SalaryFundService.workingDays(YearMonth.from(day));
        return "Оклады группы «Цех» — " + String.join(", ", pos) + " — " + F.money(sum) + " в месяц ÷ " + wd + NB
                + F.plural(wd, "рабочий день", "рабочих дня", "рабочих дней") + ". Делится между заявками дня " + mode + ".";
    }
}
