package com.confectionery.crm.service;

import com.confectionery.crm.api.dto.ProductionCostDto;
import com.confectionery.crm.api.dto.ReportDto;
import com.confectionery.crm.domain.Employee;
import com.confectionery.crm.domain.EmployeeCostGroup;
import com.confectionery.crm.domain.Order;
import com.confectionery.crm.domain.OrderItem;
import com.confectionery.crm.domain.OrderStatus;
import com.confectionery.crm.domain.Product;
import com.confectionery.crm.domain.ProductReturn;
import com.confectionery.crm.domain.ProductReturnItem;
import com.confectionery.crm.domain.ProductionBrigadeMember;
import com.confectionery.crm.domain.ProductionRequest;
import com.confectionery.crm.domain.ProductionRequestLine;
import com.confectionery.crm.domain.ProductionRequestStatus;
import com.confectionery.crm.domain.Purchase;
import com.confectionery.crm.domain.PurchaseItem;
import com.confectionery.crm.repository.EmployeeRepository;
import com.confectionery.crm.repository.ProductRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;

/**
 * Меню «Отчёты» (см. {@link ReportDto}). Продажа — отгруженные заказы (DELIVERED/COMPLETED) по
 * дате отгрузки, как на дашборде; возвраты — по дате возврата. showMoney=false — роль не видит
 * зарплату и деньги (технолог, склад ГП): денежные колонки не отдаются.
 */
@Service
@RequiredArgsConstructor
public class ReportService {

    private static final List<OrderStatus> SHIPPED = List.of(OrderStatus.DELIVERED, OrderStatus.COMPLETED);
    private static final List<OrderStatus> OPEN = List.of(OrderStatus.NEW, OrderStatus.CONFIRMED,
            OrderStatus.IN_PRODUCTION, OrderStatus.READY);

    private static final String TEXT = "TEXT", MONEY = "MONEY", QTY = "QTY", INT = "INT";

    private final EntityManager em;
    private final ProductRepository productRepository;
    private final EmployeeRepository employeeRepository;
    private final SalaryFundService salaryFundService;
    private final ProductionCostService productionCostService;

    public enum Kind {
        SHIFT_RECEIPT("shift-receipt", "Оприход за смену",
                "План и оприходовано по заявкам дня, остаток склада и неотгруженные заказы — хватает ли товара", "DAY", "TODAY"),
        EMPLOYEES("employees", "Сотрудники: смены и зарплата",
                "Сколько смен отработал каждый в бригадах, сколько пропустил, начисление за период", "PERIOD", "MONTH"),
        CLIENTS("clients", "Клиенты: продажи и возвраты",
                "Сколько купил каждый клиент, возвраты, итог за минусом возвратов", "PERIOD", "MONTH"),
        PRODUCTS("products", "Товары: продажи",
                "Продано и возвращено по каждому товару — что продаётся лучше", "PERIOD", "MONTH"),
        PURCHASES("purchases", "Закупки по поставщикам",
                "На какую сумму закуплено сырья у каждого поставщика", "PERIOD", "MONTH"),
        SUMMARY("summary", "Сводка за период",
                "Продажи, себестоимость производства, закупки и зарплата — итог периода", "PERIOD", "MONTH");

        final String key, title, description, period, defaultRange;

        Kind(String key, String title, String description, String period, String defaultRange) {
            this.key = key;
            this.title = title;
            this.description = description;
            this.period = period;
            this.defaultRange = defaultRange;
        }

        public static Kind of(String key) {
            return Arrays.stream(values()).filter(k -> k.key.equals(key)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Нет такого отчёта: " + key));
        }

        public ReportDto.Info info() {
            return new ReportDto.Info(key, title, description, period, defaultRange);
        }
    }

    /** Какие отчёты доступны роли. */
    public static List<Kind> available(String role) {
        return switch (role == null ? "" : role) {
            case "ADMIN", "ACCOUNTANT" -> List.of(Kind.values());
            case "TECHNOLOGIST" -> List.of(Kind.SHIFT_RECEIPT, Kind.EMPLOYEES);
            case "WAREHOUSE_FG" -> List.of(Kind.SHIFT_RECEIPT);
            default -> List.of();
        };
    }

    @Transactional(readOnly = true)
    public ReportDto build(Kind kind, LocalDate from, LocalDate to, boolean showMoney) {
        if (to.isBefore(from)) throw new IllegalArgumentException("Дата «по» раньше даты «с»");
        return switch (kind) {
            case SHIFT_RECEIPT -> shiftReceipt(from);
            case EMPLOYEES -> employees(from, to, showMoney);
            case CLIENTS -> clients(from, to);
            case PRODUCTS -> products(from, to);
            case PURCHASES -> purchases(from, to);
            case SUMMARY -> summary(from, to);
        };
    }

    // ---------------------------------------------------------------- Оприход за смену

    private ReportDto shiftReceipt(LocalDate date) {
        List<ProductionRequest> requests = em.createQuery("""
                        select r from ProductionRequest r where r.documentDate = :d and r.status <> :cancelled""",
                        ProductionRequest.class)
                .setParameter("d", date).setParameter("cancelled", ProductionRequestStatus.CANCELLED).getResultList();
        Map<Product, BigDecimal> plan = new LinkedHashMap<>(), received = new LinkedHashMap<>();
        for (ProductionRequest r : requests) {
            for (ProductionRequestLine l : r.getLines()) {
                plan.merge(l.getProduct(), nz(l.getPlannedQuantity()), BigDecimal::add);
                if (l.getReceivedAt() != null) received.merge(l.getProduct(), nz(l.getReceivedQuantity()), BigDecimal::add);
            }
        }
        Map<Product, BigDecimal> reserved = new LinkedHashMap<>();
        for (OrderItem i : em.createQuery("select i from OrderItem i join i.order o where o.status in :open", OrderItem.class)
                .setParameter("open", OPEN).getResultList()) {
            reserved.merge(i.getProduct(), nz(i.getQuantity()), BigDecimal::add);
        }
        Set<Product> products = new TreeSet<>(Comparator.comparing(Product::getName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Product::getId));
        products.addAll(plan.keySet());
        products.addAll(reserved.keySet());

        List<ReportDto.Row> rows = new ArrayList<>();
        int shortCount = 0, notReceived = 0;
        BigDecimal planTotal = BigDecimal.ZERO, receivedTotal = BigDecimal.ZERO;
        for (Product p : products) {
            BigDecimal pl = plan.get(p);
            BigDecimal rc = received.get(p);
            BigDecimal stock = nz(p.getQuantityInStock());
            BigDecimal res = reserved.getOrDefault(p, BigDecimal.ZERO);
            BigDecimal balance = stock.subtract(res);
            if (balance.signum() < 0) shortCount++;
            if (pl != null && rc == null) notReceived++;
            if (pl != null) planTotal = planTotal.add(pl);
            if (rc != null) receivedTotal = receivedTotal.add(rc);
            String verdict = balance.signum() < 0 ? "не хватает " + qty(balance.negate())
                    : res.signum() == 0 ? "заказов нет" : "хватает, запас " + qty(balance);
            rows.add(new ReportDto.Row(Arrays.asList(p.getName(), pl, rc, pl == null || rc == null ? null : rc.subtract(pl),
                    stock, res, verdict), balance.signum() < 0 ? "bad" : res.signum() > 0 ? "good" : null));
        }
        // Нехватка — наверх.
        rows.sort(Comparator.comparing((ReportDto.Row r) -> "bad".equals(r.tone()) ? 0 : 1));
        List<ReportDto.Card> cards = List.of(
                new ReportDto.Card("Оприходовано", receivedTotal, QTY, "план " + qty(planTotal), null),
                new ReportDto.Card("Не хватает", shortCount, INT, "товаров под неотгруженные заказы", shortCount > 0 ? "bad" : "good"),
                new ReportDto.Card("Не оприходовано", notReceived, INT, "товаров из заявок дня", notReceived > 0 ? "bad" : null));
        return new ReportDto(Kind.SHIFT_RECEIPT.key, Kind.SHIFT_RECEIPT.title, date, date, cards,
                List.of(col("Товар", TEXT), col("План", QTY), col("Оприходовано", QTY), col("Отклонение", QTY),
                        col("Остаток", QTY), col("В заказах", QTY), col("Итог", TEXT)),
                rows, Arrays.asList("Итого", planTotal, receivedTotal, receivedTotal.subtract(planTotal), null, null, null),
                List.of("План и оприходовано — по заявкам на сырьё с датой этого дня.",
                        "Остаток — склад сейчас (заказы списываются при отгрузке); «В заказах» — неотгруженные заказы.",
                        "Итог = остаток − в заказах: если меньше нуля, товара под заказы не хватает."));
    }

    // ---------------------------------------------------------------- Сотрудники

    private ReportDto employees(LocalDate from, LocalDate to, boolean showMoney) {
        // Пропуски — только по прошедшим дням (сегодня включительно), иначе текущий месяц весь «пропущен».
        LocalDate lastDay = to.isAfter(LocalDate.now()) ? LocalDate.now() : to;
        int workDays = 0;
        for (LocalDate d = from; !d.isAfter(lastDay); d = d.plusDays(1)) if (SalaryFundService.isWorkingDay(d)) workDays++;

        Map<Long, Set<LocalDate>> shifts = new HashMap<>();
        Map<Long, BigDecimal> shiftPay = new HashMap<>();
        for (ProductionBrigadeMember m : em.createQuery("""
                        select m from ProductionBrigadeMember m join fetch m.employee join fetch m.request r
                        where r.documentDate between :from and :to and r.status <> :cancelled""", ProductionBrigadeMember.class)
                .setParameter("from", from).setParameter("to", to)
                .setParameter("cancelled", ProductionRequestStatus.CANCELLED).getResultList()) {
            LocalDate d = m.getRequest().getDocumentDate();
            if (shifts.computeIfAbsent(m.getEmployee().getId(), k -> new HashSet<>()).add(d)) {
                shiftPay.merge(m.getEmployee().getId(), nz(SalaryFundService.dailyRate(m.getEmployee(), d)), BigDecimal::add);
            }
        }

        List<Employee> employees = employeeRepository.findAll().stream()
                .filter(e -> e.isActive() || shifts.containsKey(e.getId()))
                .sorted(Comparator.comparing((Employee e) -> e.effectiveCostGroup().ordinal())
                        .thenComparing(Employee::getFullName, String.CASE_INSENSITIVE_ORDER))
                .toList();
        List<ReportDto.Row> rows = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        Map<EmployeeCostGroup, BigDecimal> byGroup = new EnumMap<>(EmployeeCostGroup.class);
        int absentTotal = 0, shiftsTotal = 0;
        for (Employee e : employees) {
            EmployeeCostGroup g = e.effectiveCostGroup();
            boolean brigade = g == EmployeeCostGroup.BRIGADE;
            int worked = shifts.getOrDefault(e.getId(), Set.of()).size();
            Integer absent = brigade ? Math.max(0, workDays - worked) : null;
            BigDecimal pay = brigade ? shiftPay.getOrDefault(e.getId(), BigDecimal.ZERO) : salaryForPeriod(e, from, to);
            BigDecimal rate = brigade ? SalaryFundService.dailyRate(e, from) : e.getMonthlySalary();
            if (brigade) {
                shiftsTotal += worked;
                absentTotal += absent;
            }
            total = total.add(pay);
            byGroup.merge(g, pay, BigDecimal::add);
            List<Object> cells = new ArrayList<>(Arrays.asList(e.getFullName(), g.getLabel(), e.getPosition(),
                    brigade ? (Object) worked : "оклад", absent));
            if (showMoney) {
                cells.add(rate);
                cells.add(pay);
            }
            rows.add(new ReportDto.Row(cells, !e.isActive() ? "muted" : brigade && worked == 0 ? "bad" : null));
        }
        List<ReportDto.Column> columns = new ArrayList<>(List.of(col("Сотрудник", TEXT), col("Группа", TEXT),
                col("Должность", TEXT), col("Смен", TEXT), col("Пропущено", INT)));
        List<Object> totals = new ArrayList<>(Arrays.asList("Итого", null, null, shiftsTotal, absentTotal));
        List<ReportDto.Card> cards = new ArrayList<>(List.of(
                new ReportDto.Card("Рабочих дней", workDays, INT,
                        lastDay.isBefore(to) ? "прошло, без воскресений" : "без воскресений", null),
                new ReportDto.Card("Смен бригад", shiftsTotal, INT, "пропущено " + absentTotal, null)));
        if (showMoney) {
            columns.add(col("Ставка / оклад", MONEY));
            columns.add(col("Начислено", MONEY));
            totals.add(null);
            totals.add(total);
            cards.add(new ReportDto.Card("Начислено всего", total, MONEY, null, null));
            for (EmployeeCostGroup g : EmployeeCostGroup.values()) {
                BigDecimal v = byGroup.get(g);
                if (v != null && v.signum() > 0) cards.add(new ReportDto.Card(g.getLabel(), v, MONEY, null, null));
            }
        }
        return new ReportDto(Kind.EMPLOYEES.key, Kind.EMPLOYEES.title, from, to, cards, columns, rows, totals,
                List.of("Бригада: смены — дни, когда сотрудник стоял в бригаде заявки на «Производстве»; пропущено — "
                                + "рабочие дни периода без смены. Начислено = смены × ставка (ставка за смену или оклад ÷ рабочие дни месяца).",
                        "Цех, продажи, администрация: учёт выходов не ведётся — оклад за рабочие дни периода целиком.",
                        "Удержаний (ИПН, ОПВ и т. п.) здесь нет — это начислено, не к выдаче."));
    }

    /** Оклад за рабочие дни периода: по каждому месяцу оклад ÷ рабочие дни месяца × рабочие дни периода в нём. */
    private static BigDecimal salaryForPeriod(Employee e, LocalDate from, LocalDate to) {
        if (e.getMonthlySalary() == null) return BigDecimal.ZERO;
        BigDecimal sum = BigDecimal.ZERO;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (!SalaryFundService.isWorkingDay(d)) continue;
            if (e.getHiredAt() != null && d.isBefore(e.getHiredAt())) continue;
            sum = sum.add(e.getMonthlySalary().divide(BigDecimal.valueOf(SalaryFundService.workingDays(YearMonth.from(d))),
                    6, RoundingMode.HALF_UP));
        }
        return money(sum);
    }

    // ---------------------------------------------------------------- Клиенты

    private ReportDto clients(LocalDate from, LocalDate to) {
        Map<String, BigDecimal[]> byClient = new TreeMap<>(String.CASE_INSENSITIVE_ORDER); // [продано, возвращено, заказов]
        for (Order o : shippedOrders(from, to)) {
            BigDecimal[] v = byClient.computeIfAbsent(o.getClient().getName(), k -> zeros(3));
            v[0] = v[0].add(sum(o.getItems()));
            v[2] = v[2].add(BigDecimal.ONE);
        }
        for (ProductReturn r : returns(from, to)) {
            String name = r.getClient() != null ? r.getClient().getName() : "Без клиента";
            BigDecimal[] v = byClient.computeIfAbsent(name, k -> zeros(3));
            v[1] = v[1].add(sumReturn(r));
        }
        BigDecimal sold = BigDecimal.ZERO, returned = BigDecimal.ZERO;
        for (BigDecimal[] v : byClient.values()) {
            sold = sold.add(v[0]);
            returned = returned.add(v[1]);
        }
        BigDecimal net = sold.subtract(returned);
        List<ReportDto.Row> rows = new ArrayList<>();
        byClient.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<String, BigDecimal[]> e) -> e.getValue()[0].subtract(e.getValue()[1])).reversed())
                .forEach(e -> {
                    BigDecimal[] v = e.getValue();
                    BigDecimal n = v[0].subtract(v[1]);
                    rows.add(new ReportDto.Row(Arrays.asList(e.getKey(), v[2].intValue(), v[0], v[1], n,
                            net.signum() > 0 ? n.multiply(BigDecimal.valueOf(100)).divide(net, 1, RoundingMode.HALF_UP) + " %" : null),
                            n.signum() < 0 ? "bad" : null));
                });
        List<ReportDto.Card> cards = List.of(
                new ReportDto.Card("Продажа за минусом возвратов", net, MONEY, null, null),
                new ReportDto.Card("Продано", sold, MONEY, byClient.size() + " клиентов", null),
                new ReportDto.Card("Возвраты", returned, MONEY,
                        sold.signum() > 0 ? returned.multiply(BigDecimal.valueOf(100)).divide(sold, 1, RoundingMode.HALF_UP) + " % от продаж" : null,
                        returned.signum() > 0 ? "bad" : null));
        return new ReportDto(Kind.CLIENTS.key, Kind.CLIENTS.title, from, to, cards,
                List.of(col("Клиент", TEXT), col("Заказов", INT), col("Продано", MONEY), col("Возвраты", MONEY),
                        col("Итого", MONEY), col("Доля", TEXT)),
                rows, Arrays.asList("Итого", null, sold, returned, net, null),
                List.of("Продано — отгруженные заказы по дате отгрузки; возвраты — по дате возврата."));
    }

    // ---------------------------------------------------------------- Товары

    private ReportDto products(LocalDate from, LocalDate to) {
        Map<Product, BigDecimal[]> byProduct = new HashMap<>(); // [шт, ₸, возвр шт, возвр ₸]
        for (Order o : shippedOrders(from, to)) {
            for (OrderItem i : o.getItems()) {
                BigDecimal[] v = byProduct.computeIfAbsent(i.getProduct(), k -> zeros(4));
                v[0] = v[0].add(nz(i.getQuantity()));
                v[1] = v[1].add(nz(i.getQuantity()).multiply(nz(i.getPrice())));
            }
        }
        for (ProductReturn r : returns(from, to)) {
            for (ProductReturnItem i : r.getItems()) {
                BigDecimal[] v = byProduct.computeIfAbsent(i.getProduct(), k -> zeros(4));
                v[2] = v[2].add(nz(i.getQuantity()));
                v[3] = v[3].add(nz(i.getQuantity()).multiply(nz(i.getPrice())));
            }
        }
        BigDecimal[] t = zeros(4);
        List<ReportDto.Row> rows = new ArrayList<>();
        byProduct.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<Product, BigDecimal[]> e) -> e.getValue()[1].subtract(e.getValue()[3])).reversed())
                .forEach(e -> {
                    BigDecimal[] v = e.getValue();
                    for (int k = 0; k < 4; k++) t[k] = t[k].add(v[k]);
                    rows.add(new ReportDto.Row(Arrays.asList(e.getKey().getName(), v[0], v[1], v[2], v[3],
                            v[0].subtract(v[2]), v[1].subtract(v[3])), null));
                });
        List<ReportDto.Card> cards = List.of(
                new ReportDto.Card("Продажа за минусом возвратов", t[1].subtract(t[3]), MONEY, null, null),
                new ReportDto.Card("Продано", t[0], QTY, byProduct.size() + " товаров", null),
                new ReportDto.Card("Возвращено", t[2], QTY, null, t[2].signum() > 0 ? "bad" : null));
        return new ReportDto(Kind.PRODUCTS.key, Kind.PRODUCTS.title, from, to, cards,
                List.of(col("Товар", TEXT), col("Продано", QTY), col("Сумма", MONEY), col("Возврат", QTY),
                        col("Сумма возвр.", MONEY), col("Итого шт", QTY), col("Итого ₸", MONEY)),
                rows, Arrays.asList("Итого", t[0], t[1], t[2], t[3], t[0].subtract(t[2]), t[1].subtract(t[3])),
                List.of("Продано — отгруженные заказы по дате отгрузки; возвраты — по дате возврата."));
    }

    // ---------------------------------------------------------------- Закупки

    private ReportDto purchases(LocalDate from, LocalDate to) {
        List<Purchase> purchases = em.createQuery(
                        "select p from Purchase p where p.purchaseDate between :from and :to", Purchase.class)
                .setParameter("from", from).setParameter("to", to).getResultList();
        Map<String, BigDecimal[]> bySupplier = new TreeMap<>(String.CASE_INSENSITIVE_ORDER); // [закупок, сумма]
        Map<String, Set<String>> ingredients = new HashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Purchase p : purchases) {
            String name = p.getCounterparty() != null ? p.getCounterparty().getName() : "Без поставщика";
            BigDecimal[] v = bySupplier.computeIfAbsent(name, k -> zeros(2));
            v[0] = v[0].add(BigDecimal.ONE);
            for (PurchaseItem i : p.getItems()) {
                v[1] = v[1].add(nz(i.getTotalPrice()));
                total = total.add(nz(i.getTotalPrice()));
                ingredients.computeIfAbsent(name, k -> new TreeSet<>()).add(i.getIngredient().getName());
            }
        }
        BigDecimal grand = total;
        List<ReportDto.Row> rows = bySupplier.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<String, BigDecimal[]> e) -> e.getValue()[1]).reversed())
                .map(e -> new ReportDto.Row(Arrays.asList(e.getKey(), e.getValue()[0].intValue(), e.getValue()[1],
                        grand.signum() > 0 ? e.getValue()[1].multiply(BigDecimal.valueOf(100)).divide(grand, 1, RoundingMode.HALF_UP) + " %" : null,
                        String.join(", ", ingredients.getOrDefault(e.getKey(), Set.of()))), null))
                .toList();
        return new ReportDto(Kind.PURCHASES.key, Kind.PURCHASES.title, from, to,
                List.of(new ReportDto.Card("Закуплено", total, MONEY, purchases.size() + " закупок", null),
                        new ReportDto.Card("Поставщиков", bySupplier.size(), INT, null, null)),
                List.of(col("Поставщик", TEXT), col("Закупок", INT), col("Сумма", MONEY), col("Доля", TEXT), col("Сырьё", TEXT)),
                rows, Arrays.asList("Итого", purchases.size(), total, null, null),
                List.of("По дате закупки."));
    }

    // ---------------------------------------------------------------- Сводка

    private ReportDto summary(LocalDate from, LocalDate to) {
        BigDecimal sold = BigDecimal.ZERO;
        for (Order o : shippedOrders(from, to)) sold = sold.add(sum(o.getItems()));
        BigDecimal returned = BigDecimal.ZERO;
        for (ProductReturn r : returns(from, to)) returned = returned.add(sumReturn(r));
        BigDecimal net = sold.subtract(returned);

        ProductionCostDto.Totals prod = productionCostService.periodTotals(from, to);
        BigDecimal purchases = nz(em.createQuery("""
                        select coalesce(sum(i.totalPrice), 0) from Purchase p join p.items i
                        where p.purchaseDate between :from and :to""", BigDecimal.class)
                .setParameter("from", from).setParameter("to", to).getSingleResult());
        Map<EmployeeCostGroup, BigDecimal> funds = salaryFundService.periodFunds(from, to);
        BigDecimal sales = funds.get(EmployeeCostGroup.SALES), admin = funds.get(EmployeeCostGroup.ADMIN);
        BigDecimal producedCost = nz(prod.finishedCost());
        BigDecimal gross = net.subtract(producedCost);
        BigDecimal result = gross.subtract(sales).subtract(admin);

        List<ReportDto.Row> rows = List.of(
                row("Продано (отгружено)", sold, null),
                row("Возвраты", returned.negate(), returned.signum() > 0 ? "bad" : null),
                row("Продажа за минусом возвратов", net, "good"),
                row("Себестоимость произведённой готовой продукции", producedCost.negate(), null),
                row("   в т. ч. сырьё (выдано)", prod.issuedCost(), "muted"),
                row("   в т. ч. зарплата бригад", prod.laborCost(), "muted"),
                row("   в т. ч. накладные цеха", prod.overheadCost(), "muted"),
                row("Валовая прибыль (оценка)", gross, gross.signum() < 0 ? "bad" : "good"),
                row("Оклады продаж (водители, грузчики, склад ГП)", sales.negate(), null),
                row("Оклады администрации", admin.negate(), null),
                row("Результат периода (оценка)", result, result.signum() < 0 ? "bad" : "good"),
                row("Справочно: закупки сырья", purchases, "muted"),
                row("Справочно: полуфабрикаты произведены", prod.semiFinishedCost(), "muted"));
        return new ReportDto(Kind.SUMMARY.key, Kind.SUMMARY.title, from, to,
                List.of(new ReportDto.Card("Продажа нетто", net, MONEY, null, null),
                        new ReportDto.Card("Валовая прибыль", gross, MONEY, "оценка", gross.signum() < 0 ? "bad" : "good"),
                        new ReportDto.Card("Результат", result, MONEY, "после окладов продаж и администрации",
                                result.signum() < 0 ? "bad" : "good")),
                List.of(col("Статья", TEXT), col("Сумма", MONEY)), rows, null,
                List.of("Оценка: себестоимость считается по произведённому за период (заявки × техкарты + бригады + накладные цеха), "
                                + "а не по проданному — при больших остатках на складе результат сдвигается.",
                        "Закупки — справочно: сырьё попадает в себестоимость, когда выдано в производство.",
                        "Налоги, аренда, коммунальные и прочие расходы в CRM не ведутся и здесь не учтены."));
    }

    // ---------------------------------------------------------------- общее

    private List<Order> shippedOrders(LocalDate from, LocalDate to) {
        return em.createQuery("""
                        select distinct o from Order o join fetch o.client left join fetch o.items
                        where o.status in :shipped and o.shippedAt >= :start and o.shippedAt < :end""", Order.class)
                .setParameter("shipped", SHIPPED)
                .setParameter("start", from.atStartOfDay()).setParameter("end", to.plusDays(1).atStartOfDay())
                .getResultList();
    }

    private List<ProductReturn> returns(LocalDate from, LocalDate to) {
        return em.createQuery("""
                        select distinct r from ProductReturn r left join fetch r.items
                        where r.returnDate between :from and :to""", ProductReturn.class)
                .setParameter("from", from).setParameter("to", to).getResultList();
    }

    private static BigDecimal sum(List<OrderItem> items) {
        return items.stream().map(i -> nz(i.getQuantity()).multiply(nz(i.getPrice()))).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal sumReturn(ProductReturn r) {
        return r.getItems().stream().map(i -> nz(i.getQuantity()).multiply(nz(i.getPrice()))).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static ReportDto.Row row(String label, BigDecimal value, String tone) {
        return new ReportDto.Row(Arrays.asList(label, value), tone);
    }

    private static ReportDto.Column col(String label, String type) {
        return new ReportDto.Column(label, type);
    }

    private static BigDecimal[] zeros(int n) {
        BigDecimal[] a = new BigDecimal[n];
        Arrays.fill(a, BigDecimal.ZERO);
        return a;
    }

    private static String qty(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
