package com.confectionery.crm.service;

import com.confectionery.crm.domain.Employee;
import com.confectionery.crm.domain.ProductionBrigadeMember;
import com.confectionery.crm.domain.ProductionRequestStatus;
import com.confectionery.crm.domain.TimesheetMark;
import com.confectionery.crm.repository.EmployeeRepository;
import com.confectionery.crm.repository.TimesheetMarkRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.*;

/**
 * Табель сотрудников за месяц. Ячейка дня: ручная отметка ({@link TimesheetMark}), а без неё —
 * автоматически: был в бригаде заявки на сырьё в этот день → «пришёл», воскресенье → выходной,
 * иначе пусто. При сохранении табеля пустые прошедшие дни становятся «не пришёл» — бухгалтер
 * сначала проставляет, что нужно, потом сохраняет. Начислено = Σ ставок за отработанные дни
 * (ставка за смену или оклад ÷ рабочие дни месяца, см. SalaryFundService.dailyRate) — в том
 * числе за воскресенье, если в него работали.
 */
@Service
@RequiredArgsConstructor
public class TimesheetService {

    private static final Locale RU = Locale.forLanguageTag("ru");

    private final EntityManager em;
    private final EmployeeRepository employeeRepository;
    private final TimesheetMarkRepository markRepository;

    public record Day(LocalDate date, int day, String weekday, boolean sunday, boolean future) {
    }

    /** cells/auto — по дням месяца; status: P — пришёл, A — не пришёл, O — выходной, null — пусто. */
    public record Line(Long employeeId, String name, String position, String group, List<String> cells,
                      List<Boolean> auto, int worked, int absent, BigDecimal rate, BigDecimal pay) {
    }

    public record Sheet(String month, String title, List<Day> days, List<Line> rows, BigDecimal totalPay) {
    }

    public record CellInput(Long employeeId, LocalDate date, String status) {
    }

    @Transactional(readOnly = true)
    public Sheet month(YearMonth ym, boolean showMoney) {
        LocalDate from = ym.atDay(1), to = ym.atEndOfMonth(), today = LocalDate.now();
        Map<Long, Map<LocalDate, String>> marks = new HashMap<>();
        for (TimesheetMark m : markRepository.findForPeriod(from, to)) {
            marks.computeIfAbsent(m.getEmployee().getId(), k -> new HashMap<>()).put(m.getDate(), m.getStatus());
        }
        Map<Long, Set<LocalDate>> brigade = new HashMap<>();
        for (ProductionBrigadeMember m : em.createQuery("""
                        select m from ProductionBrigadeMember m join fetch m.employee join fetch m.request r
                        where r.documentDate between :from and :to and r.status <> :cancelled""", ProductionBrigadeMember.class)
                .setParameter("from", from).setParameter("to", to)
                .setParameter("cancelled", ProductionRequestStatus.CANCELLED).getResultList()) {
            brigade.computeIfAbsent(m.getEmployee().getId(), k -> new HashSet<>()).add(m.getRequest().getDocumentDate());
        }

        List<Day> days = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            days.add(new Day(d, d.getDayOfMonth(), d.getDayOfWeek().getDisplayName(TextStyle.SHORT, RU),
                    d.getDayOfWeek() == DayOfWeek.SUNDAY, d.isAfter(today)));
        }

        List<Employee> employees = employeeRepository.findAll().stream()
                .filter(e -> e.isActive() || marks.containsKey(e.getId()) || brigade.containsKey(e.getId()))
                .sorted(Comparator.comparing((Employee e) -> e.effectiveCostGroup().ordinal())
                        .thenComparing(Employee::getFullName, String.CASE_INSENSITIVE_ORDER))
                .toList();
        List<Line> rows = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Employee e : employees) {
            Map<LocalDate, String> own = marks.getOrDefault(e.getId(), Map.of());
            Set<LocalDate> inBrigade = brigade.getOrDefault(e.getId(), Set.of());
            List<String> cells = new ArrayList<>();
            List<Boolean> auto = new ArrayList<>();
            int worked = 0, absent = 0;
            BigDecimal pay = BigDecimal.ZERO;
            for (Day day : days) {
                String s = own.get(day.date());
                boolean isAuto = s == null;
                if (s == null) {
                    s = inBrigade.contains(day.date()) ? TimesheetMark.PRESENT : day.sunday() ? TimesheetMark.DAY_OFF : null;
                }
                cells.add(s);
                auto.add(isAuto && s != null);
                if (TimesheetMark.PRESENT.equals(s)) {
                    worked++;
                    BigDecimal r = SalaryFundService.dailyRate(e, day.date());
                    if (r != null) pay = pay.add(r);
                } else if (TimesheetMark.ABSENT.equals(s)) {
                    absent++;
                }
            }
            total = total.add(pay);
            rows.add(new Line(e.getId(), e.getFullName(), e.getPosition(), e.effectiveCostGroup().getLabel(), cells, auto,
                    worked, absent, showMoney ? SalaryFundService.dailyRate(e, from) : null, showMoney ? pay : null));
        }
        String title = capitalize(ym.getMonth().getDisplayName(TextStyle.FULL_STANDALONE, RU)) + " " + ym.getYear();
        return new Sheet(ym.toString(), title, days, rows, showMoney ? total : null);
    }

    /**
     * Сохранить табель: присланные ячейки записываются отметками; пустые ячейки прошедших дней
     * (до вчера включительно) — «не пришёл»; сегодня и дальше пустые остаются без отметки —
     * бригаду на сегодня могут ещё назначить.
     */
    @Transactional
    public void save(YearMonth ym, List<CellInput> cells, String by) {
        LocalDate from = ym.atDay(1), to = ym.atEndOfMonth(), today = LocalDate.now();
        Map<String, TimesheetMark> existing = new HashMap<>();
        for (TimesheetMark m : markRepository.findForPeriod(from, to)) {
            existing.put(m.getEmployee().getId() + "|" + m.getDate(), m);
        }
        Map<Long, Employee> employees = new HashMap<>();
        for (CellInput c : cells) {
            if (c.employeeId() == null || c.date() == null || c.date().isBefore(from) || c.date().isAfter(to)) continue;
            String status = c.status();
            if (status != null && !Set.of(TimesheetMark.PRESENT, TimesheetMark.ABSENT, TimesheetMark.DAY_OFF).contains(status)) {
                throw new IllegalArgumentException("Неизвестная отметка: " + status);
            }
            if (status == null && c.date().isBefore(today)) status = TimesheetMark.ABSENT;
            String key = c.employeeId() + "|" + c.date();
            TimesheetMark m = existing.get(key);
            if (status == null) {
                if (m != null) markRepository.delete(m);
                continue;
            }
            if (m == null) {
                m = new TimesheetMark();
                m.setEmployee(employees.computeIfAbsent(c.employeeId(),
                        id -> employeeRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Сотрудник не найден"))));
                m.setDate(c.date());
            } else if (status.equals(m.getStatus())) {
                continue;
            }
            m.setStatus(status);
            m.setUpdatedBy(by);
            m.setUpdatedAt(LocalDateTime.now());
            markRepository.save(m);
        }
    }

    /** Табель в Excel: ✓ / ✗ / В по дням, воскресенья серым, итоги справа. */
    @Transactional(readOnly = true)
    public byte[] xlsx(YearMonth ym, boolean showMoney) {
        Sheet sheet = month(ym, showMoney);
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            org.apache.poi.ss.usermodel.Sheet ws = wb.createSheet("Табель " + sheet.month());
            Font bold = wb.createFont();
            bold.setBold(true);
            CellStyle title = wb.createCellStyle();
            Font big = wb.createFont();
            big.setBold(true);
            big.setFontHeightInPoints((short) 14);
            title.setFont(big);
            CellStyle head = box(wb, IndexedColors.GREY_25_PERCENT, bold, HorizontalAlignment.CENTER);
            CellStyle text = box(wb, null, null, HorizontalAlignment.LEFT);
            CellStyle mark = box(wb, null, null, HorizontalAlignment.CENTER);
            CellStyle sunday = box(wb, IndexedColors.GREY_25_PERCENT, null, HorizontalAlignment.CENTER);
            Font green = wb.createFont();
            green.setColor(IndexedColors.GREEN.getIndex());
            green.setBold(true);
            Font red = wb.createFont();
            red.setColor(IndexedColors.RED.getIndex());
            red.setBold(true);
            CellStyle present = box(wb, null, green, HorizontalAlignment.CENTER);
            CellStyle absentStyle = box(wb, null, red, HorizontalAlignment.CENTER);
            CellStyle num = box(wb, null, null, HorizontalAlignment.RIGHT);
            CellStyle money = box(wb, null, null, HorizontalAlignment.RIGHT);
            money.setDataFormat(wb.createDataFormat().getFormat("# ##0"));
            CellStyle moneyBold = box(wb, IndexedColors.GREY_25_PERCENT, bold, HorizontalAlignment.RIGHT);
            moneyBold.setDataFormat(wb.createDataFormat().getFormat("# ##0"));

            int dayCount = sheet.days().size();
            int firstDay = 3;
            int lastCol = firstDay + dayCount + 1 + (showMoney ? 2 : 0);

            Row r0 = ws.createRow(0);
            Cell t = r0.createCell(0);
            t.setCellValue("Табель учёта рабочего времени — " + sheet.title());
            t.setCellStyle(title);
            ws.addMergedRegion(new CellRangeAddress(0, 0, 0, Math.min(lastCol, 12)));

            Row h1 = ws.createRow(2), h2 = ws.createRow(3);
            String[] left = {"№", "Сотрудник", "Должность"};
            for (int i = 0; i < left.length; i++) {
                cell(h1, i, left[i], head);
                cell(h2, i, "", head);
                ws.addMergedRegion(new CellRangeAddress(2, 3, i, i));
            }
            for (int i = 0; i < dayCount; i++) {
                Day d = sheet.days().get(i);
                cell(h1, firstDay + i, String.valueOf(d.day()), d.sunday() ? sunday : head);
                cell(h2, firstDay + i, d.weekday(), d.sunday() ? sunday : head);
                ws.setColumnWidth(firstDay + i, 4 * 256);
            }
            List<String> right = new ArrayList<>(List.of("Отраб.", "Пропуск"));
            if (showMoney) right.addAll(List.of("Ставка", "Начислено"));
            for (int i = 0; i < right.size(); i++) {
                int c = firstDay + dayCount + i;
                cell(h1, c, right.get(i), head);
                cell(h2, c, "", head);
                ws.addMergedRegion(new CellRangeAddress(2, 3, c, c));
                ws.setColumnWidth(c, (i >= 2 ? 12 : 8) * 256);
            }

            int rowIdx = 4, n = 1;
            for (Line row : sheet.rows()) {
                Row x = ws.createRow(rowIdx++);
                cell(x, 0, String.valueOf(n++), num);
                cell(x, 1, row.name(), text);
                cell(x, 2, row.position() == null ? "" : row.position(), text);
                for (int i = 0; i < dayCount; i++) {
                    String s = row.cells().get(i);
                    boolean sun = sheet.days().get(i).sunday();
                    String v = s == null ? "" : switch (s) {
                        case TimesheetMark.PRESENT -> "✓";
                        case TimesheetMark.ABSENT -> "✗";
                        default -> "В";
                    };
                    CellStyle st = TimesheetMark.PRESENT.equals(s) ? present
                            : TimesheetMark.ABSENT.equals(s) ? absentStyle : sun ? sunday : mark;
                    cell(x, firstDay + i, v, st);
                }
                numCell(x, firstDay + dayCount, row.worked(), num);
                numCell(x, firstDay + dayCount + 1, row.absent(), num);
                if (showMoney) {
                    numCell(x, firstDay + dayCount + 2, row.rate() == null ? 0 : row.rate().doubleValue(), money);
                    numCell(x, firstDay + dayCount + 3, row.pay() == null ? 0 : row.pay().doubleValue(), money);
                }
            }
            if (showMoney) {
                Row x = ws.createRow(rowIdx + 1);
                cell(x, 1, "Итого начислено", head);
                numCell(x, firstDay + dayCount + 3, sheet.totalPay().doubleValue(), moneyBold);
            }
            Row legend = ws.createRow(rowIdx + 3);
            legend.createCell(1).setCellValue("✓ — пришёл, ✗ — не пришёл, В — выходной");

            ws.setColumnWidth(0, 4 * 256);
            ws.setColumnWidth(1, 34 * 256);
            ws.setColumnWidth(2, 18 * 256);
            ws.createFreezePane(3, 4);
            ws.getPrintSetup().setLandscape(true);
            ws.setFitToPage(true);
            ws.getPrintSetup().setFitWidth((short) 1);
            ws.getPrintSetup().setFitHeight((short) 0);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось сформировать Excel: " + e.getMessage(), e);
        }
    }

    private static CellStyle box(Workbook wb, IndexedColors fill, Font font, HorizontalAlignment align) {
        CellStyle s = wb.createCellStyle();
        s.setBorderTop(BorderStyle.THIN);
        s.setBorderBottom(BorderStyle.THIN);
        s.setBorderLeft(BorderStyle.THIN);
        s.setBorderRight(BorderStyle.THIN);
        s.setAlignment(align);
        s.setVerticalAlignment(VerticalAlignment.CENTER);
        if (fill != null) {
            s.setFillForegroundColor(fill.getIndex());
            s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        }
        if (font != null) s.setFont(font);
        return s;
    }

    private static void cell(Row row, int col, String value, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellValue(value);
        c.setCellStyle(style);
    }

    private static void numCell(Row row, int col, double value, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellValue(value);
        c.setCellStyle(style);
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(RU) + s.substring(1);
    }
}
