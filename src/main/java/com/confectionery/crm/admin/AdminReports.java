package com.confectionery.crm.admin;

import com.confectionery.crm.api.dto.ProductionCostDto;
import com.confectionery.crm.domain.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

import static com.confectionery.crm.domain.Role.*;

/**
 * Отчёты по макету (часть 4). Как ReportService: продажа — выданные заказы (DELIVERED, COMPLETED) по дню отгрузки,
 * возвраты — по дню возврата, итого — продано минус возвращено. Себестоимость выпуска — как «Производство»
 * (ProductionCostService) по дням периода, только оприходованное. Себестоимость проданного — проданные штуки за минусом
 * возвращённых × себестоимость 1 шт по выпускам за 30 дней по конец периода. Сводка — продажа нетто минус себестоимость
 * проданного и оклады продаж и администрации (оценка: аренда, коммунальные, налоги и закупки впрок не учтены).
 */
public final class AdminReports {

    private AdminReports() {
    }

    static final AdminFormat F = new AdminFormat();
    static final String NB = AdminFormat.NB;
    static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    public static final List<OrderStatus> SHIPPED = List.of(OrderStatus.DELIVERED, OrderStatus.COMPLETED);
    static final String[] MONTH = AdminStaffController.MONTH;

    // ------------------------------------------------------------------ список отчётов

    /** Отчёт: ключ (адрес), группа на странице отчётов, значок, кто видит кроме администратора, ссылка в другой раздел. */
    public record Rep(String key, String group, String icon, String title, String desc, Set<Role> roles, String href,
                      boolean day, String who) {
        public boolean visibleTo(Role r) {
            return r == ADMIN || roles.contains(r);
        }

        public String link() {
            return href != null ? href : "/admin/reports/" + key;
        }
    }

    static final Set<Role> SELL = EnumSet.of(ACCOUNTANT, WAREHOUSE_FG);

    public static final List<Rep> ALL = List.of(
            new Rep("sales", "Продажи", "cash", "Продажи по дням", "Выручка, заказы и штуки по дням, возвраты — за любой период", SELL, null, false, "бухгалтер, склад ГП"),
            new Rep("products", "Продажи", "products", "Товары: продажи", "Что продаётся лучше — в штуках и в тенге, сколько вернули", SELL, null, false, "бухгалтер, склад ГП"),
            new Rep("clients", "Продажи", "clients", "Клиенты: продажи и возвраты", "Сколько купил каждый клиент и какую часть вернул", SELL, null, false, "бухгалтер, склад ГП"),
            new Rep("returns", "Продажи", "returns", "Возвраты", "По товарам и клиентам, в штуках — в разделе «Возвраты»", SELL, "/admin/returns", false, "продажи, бухгалтер, склад ГП"),
            new Rep("shift", "Производство", "production", "Оприход за смену", "Сколько оприходовали за день и хватает ли товара на заказы",
                    EnumSet.of(ACCOUNTANT, TECHNOLOGIST, WAREHOUSE_FG), null, true, "бухгалтер, технолог, склад ГП"),
            new Rep("cost", "Производство", "techcards", "Себестоимость по товарам", "Выпуск за период: себестоимость за 1 шт, цена и наценка по каждому товару",
                    EnumSet.of(ACCOUNTANT), null, false, "бухгалтер"),
            new Rep("purchases", "Закупки и люди", "purchases", "Закупки по поставщикам", "Сколько и на какую сумму купили у каждого поставщика",
                    EnumSet.of(ACCOUNTANT), null, false, "бухгалтер"),
            new Rep("staff", "Закупки и люди", "staff", "Табель сотрудников", "Кто пришёл по дням месяца, смены и начислено — в «Сотрудниках»",
                    EnumSet.of(ACCOUNTANT, TECHNOLOGIST), "/admin/staff/timesheet", false, "бухгалтер, технолог"),
            new Rep("summary", "Деньги", "reports", "Сводка за период", "Продажи минус возвраты, себестоимость и оклады — оценка результата",
                    EnumSet.of(ACCOUNTANT), null, false, "бухгалтер"));

    public static Rep byKey(String key) {
        return ALL.stream().filter(r -> r.key().equals(key)).findFirst().orElse(null);
    }

    // ------------------------------------------------------------------ период

    public record Per(String key, String label, boolean on, String href) {
    }

    public record Range(String per, LocalDate from, LocalDate to) {
        public String text() {
            return from.equals(to) ? F.dml(from) + " " + from.getYear() : F.dm(from) + " — " + F.dml(to) + " " + to.getYear();
        }

        public String query() {
            return "per=" + per + (per.equals("own") ? "&from=" + from + "&to=" + to : "");
        }

        public String file() {
            return from.equals(to) ? from.toString() : from + "—" + to;
        }
    }

    public static List<Per> pers(String key, Range cur, LocalDate today) {
        int m = today.getMonthValue();
        String[][] p = {{"today", "Сегодня"}, {"yest", "Вчера"}, {"7", "7 дней"}, {"month", F.cap(MONTH[m - 1])},
                {"prev", F.cap(MONTH[(m + 10) % 12])}, {"own", "Свой период"}};
        List<Per> out = new ArrayList<>();
        for (String[] x : p) {
            String href = "/admin/reports/" + key + (x[0].equals("month") ? "" : "?per=" + x[0]
                    + (x[0].equals("own") ? "&from=" + cur.from() + "&to=" + cur.to() : ""));
            out.add(new Per(x[0], x[1], x[0].equals(cur.per()), href));
        }
        return out;
    }

    public static Range range(String per, String from, String to, LocalDate today) {
        String p = per == null ? "month" : per;
        YearMonth ym = YearMonth.from(today);
        return switch (p) {
            case "today" -> new Range(p, today, today);
            case "yest" -> new Range(p, today.minusDays(1), today.minusDays(1));
            case "7" -> new Range(p, today.minusDays(6), today);
            case "prev" -> new Range(p, ym.minusMonths(1).atDay(1), ym.minusMonths(1).atEndOfMonth());
            case "own" -> {
                LocalDate a = parse(from, ym.atDay(1)), b = parse(to, today);
                if (b.isAfter(today)) b = today;
                if (a.isAfter(b)) {
                    LocalDate x = a;
                    a = b;
                    b = x;
                }
                if (a.isBefore(b.minusDays(366))) a = b.minusDays(366);
                yield new Range(p, a, b);
            }
            default -> new Range("month", ym.atDay(1), today);
        };
    }

    static LocalDate parse(String s, LocalDate fallback) {
        try {
            return s == null || s.isBlank() ? fallback : LocalDate.parse(s);
        } catch (Exception e) {
            return fallback;
        }
    }

    // ------------------------------------------------------------------ плитки

    /** Плитка: подпись, значение (html: «1,2 млн <small>₸</small>»), пояснение (html), класс (kpi--warm, kpi--alert). */
    public record Kpi(String label, String value, String sub, String cls) {
        /** Значение-текст (название товара, поставщика) — меньшим шрифтом. */
        public boolean text() {
            String v = value.replaceAll("<[^>]+>", "");
            return !v.isEmpty() && !v.substring(0, 1).matches("[\\d−—-]");
        }
    }

    static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    public static String big(BigDecimal v) {
        return (v.signum() < 0 ? "−" : "") + AdminWarehouseController.shortMoney(v.abs()) + " <small>₸</small>";
    }

    public static String pcs(BigDecimal v) {
        return F.num(v) + " <small>шт</small>";
    }

    /** part ÷ whole × 100, null — если whole = 0. */
    public static BigDecimal share(BigDecimal part, BigDecimal whole) {
        return whole == null || whole.signum() <= 0 ? null : part.multiply(HUNDRED).divide(whole, 4, RoundingMode.HALF_UP);
    }

    public static String pctText(BigDecimal v, int d) {
        return v == null ? "—" : F.dec(v, d) + NB + "%";
    }

    /** Ширина полоски доли (в процентах ширины), от 2 %. */
    public static int bar(BigDecimal v, BigDecimal max) {
        if (max == null || max.signum() <= 0 || v == null || v.signum() <= 0) return 0;
        return Math.max(2, v.multiply(HUNDRED).divide(max, 0, RoundingMode.HALF_UP).intValue());
    }

    // ------------------------------------------------------------------ продажи

    public static final class Day {
        public final LocalDate d;
        public int n;
        public BigDecimal pcs = BigDecimal.ZERO, sold = BigDecimal.ZERO, ret = BigDecimal.ZERO, rpcs = BigDecimal.ZERO;

        Day(LocalDate d) {
            this.d = d;
        }

        public BigDecimal net() {
            return sold.subtract(ret);
        }

        public boolean sunday() {
            return d.getDayOfWeek() == java.time.DayOfWeek.SUNDAY;
        }

        public LocalDate getD() {
            return d;
        }
    }

    public static final class Prod {
        public final Product p;
        public BigDecimal q = BigDecimal.ZERO, s = BigDecimal.ZERO, rq = BigDecimal.ZERO, rs = BigDecimal.ZERO;

        Prod(Product p) {
            this.p = p;
        }

        public BigDecimal net() {
            return s.subtract(rs);
        }

        /** Какую часть проданного вернули, % (null — не продавали). */
        public BigDecimal retPct() {
            return share(rq, q);
        }

        public Product getP() {
            return p;
        }

        public String photo() {
            return AdminOrders.photo(p);
        }

        public boolean retBad() {
            return retPct() != null && rq.signum() > 0 && retPct().compareTo(BigDecimal.TEN) >= 0;
        }

        public String retText() {
            return retPct() != null && rq.signum() > 0 ? pctText(retPct(), 1) : "—";
        }

        public String qText() {
            return q.signum() > 0 ? F.num(q) + NB + "шт" : "—";
        }

        public String rqText() {
            return rq.signum() > 0 ? F.num(rq) + NB + "шт" : "—";
        }

        public int w(BigDecimal max) {
            return bar(net(), max);
        }
    }

    public static final class Cl {
        public final Client c;
        public final String name;
        public int n;
        public BigDecimal s = BigDecimal.ZERO, r = BigDecimal.ZERO;

        Cl(Client c, String name) {
            this.c = c;
            this.name = name;
        }

        public BigDecimal net() {
            return s.subtract(r);
        }

        public BigDecimal retPct() {
            return share(r, s);
        }

        public Client getC() {
            return c;
        }

        public boolean retBad() {
            return retPct() != null && r.signum() > 0 && retPct().compareTo(BigDecimal.TEN) >= 0;
        }

        public String retText() {
            return retPct() != null && r.signum() > 0 ? pctText(retPct(), 1) : "—";
        }

        public int w(BigDecimal max) {
            return bar(net(), max);
        }

        public String getName() {
            return name;
        }
    }

    public static final class Sales {
        public final List<Day> days = new ArrayList<>();
        public final Map<Long, Prod> prods = new LinkedHashMap<>();
        public final Map<String, Cl> cls = new LinkedHashMap<>();
        public int n;
        public BigDecimal pcs = BigDecimal.ZERO, sold = BigDecimal.ZERO, ret = BigDecimal.ZERO, rpcs = BigDecimal.ZERO;

        public BigDecimal net() {
            return sold.subtract(ret);
        }

        public BigDecimal retPct() {
            return share(ret, sold);
        }

        public String retText() {
            return retPct() == null ? "—" : pctText(retPct(), 1);
        }

        public String pcsText() {
            return F.num(pcs) + NB + "шт";
        }

        public String rpcsText() {
            return F.num(rpcs) + NB + "шт";
        }

        /** Какую часть проданных штук вернули. */
        public String pcsRetText() {
            return pcs.signum() > 0 ? pctText(share(rpcs, pcs), 1) : "—";
        }

        /** Товары: сначала что принесло больше денег. */
        public List<Prod> prodList() {
            List<Prod> l = new ArrayList<>(prods.values());
            l.sort(Comparator.comparing(Prod::net).reversed().thenComparing(x -> x.p.getName()));
            return l;
        }

        public List<Cl> clList() {
            List<Cl> l = new ArrayList<>(cls.values());
            l.sort(Comparator.comparing(Cl::net).reversed().thenComparing(x -> x.name));
            return l;
        }
    }

    static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    public static Sales sales(Collection<Order> shipped, Collection<ProductReturn> returns, LocalDate from, LocalDate to) {
        Sales s = new Sales();
        Map<LocalDate, Day> byDay = new LinkedHashMap<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) byDay.put(d, new Day(d));
        for (Order o : shipped) {
            if (!SHIPPED.contains(o.getStatus()) || o.getShippedAt() == null) continue;
            Day x = byDay.get(o.getShippedAt().toLocalDate());
            if (x == null) continue;
            BigDecimal sum = BigDecimal.ZERO, q = BigDecimal.ZERO;
            for (OrderItem i : o.getItems()) {
                BigDecimal line = nz(i.getQuantity()).multiply(nz(i.getPrice()));
                sum = sum.add(line);
                q = q.add(nz(i.getQuantity()));
                Prod p = s.prods.computeIfAbsent(i.getProduct().getId(), k -> new Prod(i.getProduct()));
                p.q = p.q.add(nz(i.getQuantity()));
                p.s = p.s.add(line);
            }
            x.n++;
            x.pcs = x.pcs.add(q);
            x.sold = x.sold.add(sum);
            s.n++;
            s.pcs = s.pcs.add(q);
            s.sold = s.sold.add(sum);
            Cl c = cl(s, o.getClient());
            c.n++;
            c.s = c.s.add(sum);
        }
        for (ProductReturn r : returns) {
            Day x = byDay.get(r.getReturnDate());
            if (x == null) continue;
            BigDecimal sum = BigDecimal.ZERO, q = BigDecimal.ZERO;
            for (ProductReturnItem i : r.getItems()) {
                BigDecimal line = nz(i.getQuantity()).multiply(nz(i.getPrice()));
                sum = sum.add(line);
                q = q.add(nz(i.getQuantity()));
                Prod p = s.prods.computeIfAbsent(i.getProduct().getId(), k -> new Prod(i.getProduct()));
                p.rq = p.rq.add(nz(i.getQuantity()));
                p.rs = p.rs.add(line);
            }
            x.ret = x.ret.add(sum);
            x.rpcs = x.rpcs.add(q);
            s.ret = s.ret.add(sum);
            s.rpcs = s.rpcs.add(q);
            Client client = r.getClient() != null ? r.getClient() : r.getOrder() != null ? r.getOrder().getClient() : null;
            Cl c = cl(s, client);
            c.r = c.r.add(sum);
        }
        s.days.addAll(byDay.values());
        return s;
    }

    private static Cl cl(Sales s, Client c) {
        String key = c == null ? "-" : String.valueOf(c.getId());
        return s.cls.computeIfAbsent(key, k -> new Cl(c, c == null ? "Без клиента" : c.getName()));
    }

    // ------------------------------------------------------------------ себестоимость

    public static final class CostProd {
        public final Long id;
        public final String name;
        public final boolean semi;
        public BigDecimal q = BigDecimal.ZERO, cost = BigDecimal.ZERO, sale;
        public final List<Long> reqs = new ArrayList<>();

        CostProd(Long id, String name, boolean semi) {
            this.id = id;
            this.name = name;
            this.semi = semi;
        }

        public BigDecimal unit() {
            return q.signum() > 0 ? cost.divide(q, 2, RoundingMode.HALF_UP) : null;
        }

        public BigDecimal markup() {
            return AdminProduction.markup(sale, unit());
        }

        public boolean low() {
            BigDecimal m = markup();
            return m != null && m.compareTo(BigDecimal.valueOf(20)) < 0;
        }

        public String reqText() {
            return "заявки № " + String.join(", ", reqs.stream().limit(4).map(String::valueOf).toList()) + (reqs.size() > 4 ? "…" : "");
        }

        public Long getId() {
            return id;
        }

        public String qText() {
            return F.num(q) + NB + "шт";
        }

        public String markupText() {
            return markup() == null ? null : AdminProduction.pct(markup(), 0);
        }
    }

    public static final class Cost {
        public final Map<Long, CostProd> prods = new LinkedHashMap<>();
        public BigDecimal fg = BigDecimal.ZERO, sale = BigDecimal.ZERO, semi = BigDecimal.ZERO, units = BigDecimal.ZERO;
        public final Set<String> unpriced = new TreeSet<>();

        public BigDecimal markup() {
            return AdminProduction.markup(sale, fg);
        }

        public String unpricedText() {
            return String.join(", ", unpriced);
        }

        public List<CostProd> list() {
            List<CostProd> l = new ArrayList<>(prods.values());
            l.sort(Comparator.comparing((CostProd x) -> x.cost).reversed());
            return l;
        }

        public List<CostProd> low() {
            return list().stream().filter(CostProd::low).toList();
        }

        public long finished() {
            return prods.values().stream().filter(x -> !x.semi).count();
        }

        /** Себестоимость 1 шт готового товара. */
        public Map<Long, BigDecimal> unit() {
            Map<Long, BigDecimal> out = new HashMap<>();
            prods.values().forEach(p -> {
                if (!p.semi && p.q.signum() > 0) out.put(p.id, p.cost.divide(p.q, 8, RoundingMode.HALF_UP));
            });
            return out;
        }
    }

    /** Себестоимость оприходованного по дням (ProductionCostService.days): строки заявок с quantity > 0 и received. */
    public static Cost cost(Collection<ProductionCostDto> days) {
        Cost c = new Cost();
        for (ProductionCostDto day : days) {
            if (day.unpricedIngredients() != null) c.unpriced.addAll(day.unpricedIngredients());
            for (ProductionCostDto.Request r : day.requests()) {
                for (ProductionCostDto.Line y : r.lines()) {
                    if (!y.received() || y.quantity() == null || y.quantity().signum() <= 0) continue;
                    CostProd p = c.prods.computeIfAbsent(y.productId(), k -> new CostProd(y.productId(), y.name(), y.semiFinished()));
                    p.q = p.q.add(y.quantity());
                    p.cost = p.cost.add(nz(y.cost()));
                    if (y.salePrice() != null) p.sale = y.salePrice();
                    if (!p.reqs.contains(r.id())) p.reqs.add(r.id());
                    if (y.semiFinished()) c.semi = c.semi.add(nz(y.cost()));
                    else {
                        c.fg = c.fg.add(nz(y.cost()));
                        c.units = c.units.add(y.quantity());
                        if (y.salePrice() != null) c.sale = c.sale.add(y.salePrice().multiply(y.quantity()));
                    }
                }
            }
        }
        return c;
    }

    /** Себестоимость проданного: продано штук за минусом возвращённых × себестоимость 1 шт; без себестоимости — отдельно. */
    public record Cogs(BigDecimal cost, BigDecimal noPcs, List<String> no) {
        public String noText() {
            return String.join(", ", no);
        }
    }

    public static Cogs cogs(Sales s, Map<Long, BigDecimal> unit) {
        BigDecimal cost = BigDecimal.ZERO, noPcs = BigDecimal.ZERO;
        List<String> no = new ArrayList<>();
        for (Prod p : s.prodList()) {
            BigDecimal u = unit.get(p.p.getId());
            if (u != null) cost = cost.add(u.multiply(p.q.subtract(p.rq)));
            else if (p.q.signum() > 0) {
                noPcs = noPcs.add(p.q);
                no.add(AdminRequests.shortName(p.p.getName()));
            }
        }
        return new Cogs(cost.setScale(0, RoundingMode.HALF_UP), noPcs, no);
    }

    // ------------------------------------------------------------------ закупки

    public static final class Sup {
        public final String name;
        public int n;
        public BigDecimal s = BigDecimal.ZERO;
        public LocalDate last;

        Sup(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        public int w(BigDecimal max) {
            return bar(s, max);
        }
    }

    public record Pur(List<Sup> list, BigDecimal total, int n, int err) {
        public Sup top() {
            return list.isEmpty() ? null : list.get(0);
        }
    }

    public static Pur purchases(Collection<Purchase> all, LocalDate from, LocalDate to) {
        Map<String, Sup> by = new LinkedHashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        int n = 0, err = 0;
        for (Purchase p : all) {
            LocalDate d = p.getPurchaseDate();
            if (d == null || d.isBefore(from) || d.isAfter(to)) continue;
            String name = p.getCounterparty() != null ? p.getCounterparty().getName() : "Без поставщика";
            Sup x = by.computeIfAbsent(name, Sup::new);
            BigDecimal sum = BigDecimal.ZERO;
            for (PurchaseItem i : p.getItems()) sum = sum.add(nz(i.getTotalPrice()));
            x.n++;
            x.s = x.s.add(sum);
            if (x.last == null || d.isAfter(x.last)) x.last = d;
            total = total.add(sum);
            n++;
            if (p.getOneCSyncError() != null && !p.getOneCSyncError().isBlank()) err++;
        }
        List<Sup> list = new ArrayList<>(by.values());
        list.sort(Comparator.comparing((Sup x) -> x.s).reversed());
        return new Pur(list, total, n, err);
    }

    // ------------------------------------------------------------------ оприход за смену

    public record ShiftRow(Product p, BigDecimal plan, BigDecimal got, BigDecimal stock, BigDecimal rq) {
        public BigDecimal free() {
            return stock.subtract(rq);
        }

        public boolean shortage() {
            return free().signum() < 0;
        }
    }

    public record Shift(List<ShiftRow> rows, BigDecimal got, BigDecimal plan, BigDecimal inOrders, int shortCount, int reqs) {
    }

    public static Shift shift(List<ProductionRequest> dayReqs, Map<Long, AdminStock.Res> reserve, Collection<Product> products) {
        Map<Long, Product> byId = new HashMap<>();
        products.forEach(p -> byId.put(p.getId(), p));
        Map<Long, BigDecimal> plan = new LinkedHashMap<>(), got = new HashMap<>();
        int reqs = 0;
        for (ProductionRequest r : dayReqs) {
            if (r.getStatus() == ProductionRequestStatus.CANCELLED) continue;
            reqs++;
            for (ProductionRequestLine l : r.getLines()) {
                Product p = l.getProduct();
                if (p.isSemiFinished()) continue;
                byId.putIfAbsent(p.getId(), p);
                plan.merge(p.getId(), nz(l.getPlannedQuantity()), BigDecimal::add);
                if (l.getReceivedAt() != null) got.merge(p.getId(), nz(l.getReceivedQuantity()), BigDecimal::add);
            }
        }
        Set<Long> keys = new LinkedHashSet<>(plan.keySet());
        reserve.forEach((k, v) -> {
            if (v.q().signum() > 0 && byId.containsKey(k)) keys.add(k);
        });
        List<ShiftRow> rows = new ArrayList<>();
        BigDecimal gotT = BigDecimal.ZERO, planT = BigDecimal.ZERO, inOrders = BigDecimal.ZERO;
        int sh = 0;
        for (Long k : keys) {
            Product p = byId.get(k);
            AdminStock.Res res = reserve.get(k);
            ShiftRow x = new ShiftRow(p, plan.get(k), got.get(k), nz(p.getQuantityInStock()), res == null ? BigDecimal.ZERO : res.q());
            rows.add(x);
            if (x.got() != null) gotT = gotT.add(x.got());
            if (x.plan() != null) planT = planT.add(x.plan());
            inOrders = inOrders.add(x.rq());
            if (x.shortage()) sh++;
        }
        rows.sort(Comparator.comparing((ShiftRow x) -> x.shortage() ? 0 : 1).thenComparing(x -> x.p().getName(), AdminTeam.RU::compare));
        return new Shift(rows, gotT, planT, inOrders, sh, reqs);
    }
}
