package com.confectionery.crm.service;

import com.confectionery.crm.domain.Employee;
import com.confectionery.crm.domain.TimesheetMark;
import com.confectionery.crm.repository.EmployeeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Табель: воскресенье — выходной, при сохранении пустые прошедшие дни → «не пришёл», Excel строится. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:timesheet;DB_CLOSE_DELAY=-1",
        "crm.onec.base-url=",
        "spring.task.scheduling.enabled=false"
})
class TimesheetServiceTest {

    @Autowired TimesheetService timesheetService;
    @Autowired EmployeeRepository employeeRepository;

    @Test
    void sundayDayOffAndSaveMarksEmptyAsAbsent() {
        Employee e = new Employee();
        e.setFullName("Тестов Тест");
        e.setPosition("Пекарь");
        e.setMonthlySalary(new BigDecimal("260000"));
        e = employeeRepository.save(e);
        YearMonth ym = YearMonth.of(2026, 9); // прошедший месяц: все дни в прошлом
        LocalDate sunday = ym.atDay(1);
        while (sunday.getDayOfWeek() != DayOfWeek.SUNDAY) sunday = sunday.plusDays(1);

        TimesheetService.Line row = row(ym, e.getId());
        assertThat(row.cells().get(sunday.getDayOfMonth() - 1)).isEqualTo(TimesheetMark.DAY_OFF);
        assertThat(row.cells().get(0)).isNull();

        // Отметили 1-е «пришёл» и воскресенье «пришёл» (перекличка в выходной), остальное пусто.
        List<TimesheetService.CellInput> cells = new ArrayList<>();
        for (int d = 1; d <= ym.lengthOfMonth(); d++) {
            LocalDate date = ym.atDay(d);
            String s = d == 1 || date.equals(sunday) ? TimesheetMark.PRESENT
                    : date.getDayOfWeek() == DayOfWeek.SUNDAY ? TimesheetMark.DAY_OFF : null;
            cells.add(new TimesheetService.CellInput(e.getId(), date, s));
        }
        timesheetService.save(ym, cells, "test");

        row = row(ym, e.getId());
        int sundays = (int) ym.atDay(1).datesUntil(ym.atEndOfMonth().plusDays(1))
                .filter(d -> d.getDayOfWeek() == DayOfWeek.SUNDAY).count();
        assertThat(row.worked()).isEqualTo(2);
        assertThat(row.absent()).isEqualTo(ym.lengthOfMonth() - sundays - 1);
        assertThat(row.pay()).isEqualByComparingTo(new BigDecimal("20000.00")); // 260000 / 26 × 2

        assertThat(timesheetService.xlsx(ym, true)).isNotEmpty();
    }

    private TimesheetService.Line row(YearMonth ym, Long id) {
        return timesheetService.month(ym, true).rows().stream().filter(r -> r.employeeId().equals(id)).findFirst().orElseThrow();
    }
}
