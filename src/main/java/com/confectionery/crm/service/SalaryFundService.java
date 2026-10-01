package com.confectionery.crm.service;

import com.confectionery.crm.domain.Employee;
import com.confectionery.crm.domain.EmployeeCostGroup;
import com.confectionery.crm.repository.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Фонд оплаты труда по группам затрат ({@link EmployeeCostGroup}). Месячный оклад переводится в
 * дневной делением на рабочие дни месяца — шестидневка, все дни кроме воскресений (в 1С оклад
 * «по дням»). Берутся текущие оклады работающих сотрудников, без истории — смена оклада
 * пересчитает и прошлые дни (как и ставки бригад, см. ProductionCostService).
 */
@Service
@RequiredArgsConstructor
public class SalaryFundService {

    private final EmployeeRepository employeeRepository;

    public static int workingDays(YearMonth month) {
        int days = 0;
        for (int d = 1; d <= month.lengthOfMonth(); d++) {
            if (month.atDay(d).getDayOfWeek() != DayOfWeek.SUNDAY) days++;
        }
        return days;
    }

    public static boolean isWorkingDay(LocalDate date) {
        return date.getDayOfWeek() != DayOfWeek.SUNDAY;
    }

    /** Ставка за смену: заданная вручную, иначе оклад ÷ рабочие дни месяца; null — нет ни того, ни другого. */
    public static BigDecimal dailyRate(Employee e, LocalDate date) {
        if (e.getDailyRate() != null) return e.getDailyRate();
        if (e.getMonthlySalary() == null) return null;
        return e.getMonthlySalary().divide(BigDecimal.valueOf(workingDays(YearMonth.from(date))), 2, RoundingMode.HALF_UP);
    }

    /** Дневной фонд группы на дату: Σ окладов работающих ÷ рабочие дни месяца (без проверки, рабочий ли день). */
    public BigDecimal dailyFund(EmployeeCostGroup group, LocalDate date) {
        return dailyFunds(date).get(group);
    }

    public Map<EmployeeCostGroup, BigDecimal> dailyFunds(LocalDate date) {
        return dailyFunds(date, employeeRepository.findAll());
    }

    private static Map<EmployeeCostGroup, BigDecimal> dailyFunds(LocalDate date, List<Employee> employees) {
        Map<EmployeeCostGroup, BigDecimal> monthly = new EnumMap<>(EmployeeCostGroup.class);
        for (EmployeeCostGroup g : EmployeeCostGroup.values()) monthly.put(g, BigDecimal.ZERO);
        for (Employee e : employees) {
            if (!e.isActive() || e.getMonthlySalary() == null) continue;
            monthly.merge(e.effectiveCostGroup(), e.getMonthlySalary(), BigDecimal::add);
        }
        BigDecimal days = BigDecimal.valueOf(workingDays(YearMonth.from(date)));
        Map<EmployeeCostGroup, BigDecimal> daily = new EnumMap<>(EmployeeCostGroup.class);
        monthly.forEach((g, v) -> daily.put(g, v.divide(days, 2, RoundingMode.HALF_UP)));
        return daily;
    }

    /** Фонд групп за период (только рабочие дни) — для дашборда. */
    public Map<EmployeeCostGroup, BigDecimal> periodFunds(LocalDate from, LocalDate to) {
        List<Employee> employees = employeeRepository.findAll();
        Map<EmployeeCostGroup, BigDecimal> total = new EnumMap<>(EmployeeCostGroup.class);
        for (EmployeeCostGroup g : EmployeeCostGroup.values()) total.put(g, BigDecimal.ZERO);
        Map<YearMonth, Map<EmployeeCostGroup, BigDecimal>> cache = new java.util.HashMap<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (!isWorkingDay(d)) continue;
            final LocalDate day = d;
            cache.computeIfAbsent(YearMonth.from(d), m -> dailyFunds(day, employees))
                    .forEach((g, v) -> total.merge(g, v, BigDecimal::add));
        }
        return total;
    }
}
