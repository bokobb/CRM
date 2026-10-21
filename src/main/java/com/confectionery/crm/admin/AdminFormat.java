package com.confectionery.crm.admin;

import com.confectionery.crm.service.PickupSlotService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Числа и даты админки так же, как в макете: деньги с неразрывным пробелом «68 930 ₸», минус «−»,
 * «сегодня / завтра / вчера / 15 окт», окна «10:00–11:00». В шаблонах — {@code ${@fmt.money(x)}}.
 */
@Component("fmt")
public class AdminFormat {

    public static final String NB = " ";
    private static final Locale RU = new Locale("ru");
    private static final String[] MON = {"января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа",
            "сентября", "октября", "ноября", "декабря"};
    private static final String[] MON3 = {"янв", "фев", "мар", "апр", "мая", "июн", "июл", "авг", "сен", "окт", "ноя", "дек"};
    private static final String[] WD = {"пн", "вт", "ср", "чт", "пт", "сб", "вс"};
    private static final String[] WDL = {"понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье"};

    private static DecimalFormat pattern(String p) {
        DecimalFormatSymbols sym = new DecimalFormatSymbols(RU);
        sym.setGroupingSeparator(' ');
        sym.setDecimalSeparator(',');
        sym.setMinusSign('−');
        return new DecimalFormat(p, sym);
    }

    /** 68 930 ₸ */
    public String money(BigDecimal v) {
        if (v == null) return "—";
        return pattern("#,##0").format(v.setScale(0, RoundingMode.HALF_UP)) + NB + "₸";
    }

    public String money(Number v) {
        return v == null ? "—" : money(new BigDecimal(v.toString()));
    }

    /** Число без лишних нулей с группами: 1 390, 24,8 */
    public String num(BigDecimal v) {
        if (v == null) return "—";
        return pattern("#,##0.###").format(v);
    }

    public String num(Number v) {
        return v == null ? "—" : num(new BigDecimal(v.toString()));
    }

    /** Ровно d знаков после запятой: dec(25.55, 3) = «25,550». */
    public String dec(BigDecimal v, int d) {
        if (v == null) return "—";
        return pattern(d == 0 ? "#,##0" : "#,##0." + "0".repeat(d)).format(v.setScale(d, RoundingMode.HALF_UP));
    }

    public String dec(Number v, int d) {
        return v == null ? "—" : dec(new BigDecimal(v.toString()), d);
    }

    /** То же без групп разрядов — для полей ввода: «25,550». */
    public String fix(BigDecimal v, int d) {
        if (v == null) return "";
        return v.setScale(d, RoundingMode.HALF_UP).toPlainString().replace('.', ',');
    }

    /** Количество сырья и замесов: 1 180 · 41,4 · 0,138 — до трёх знаков после запятой, без лишних нулей. */
    public String qn(BigDecimal v) {
        return AdminRequests.qn(v);
    }

    /** То же с единицей: «41,4 кг». */
    public String qu(BigDecimal v, String unit) {
        return AdminRequests.qu(v, unit);
    }

    /** Наценка, отклонение: «+61 %», «−0,6 %» (digits — знаков после запятой). */
    public String pct(BigDecimal v, int digits) {
        return AdminProduction.pct(v, digits);
    }

    public String plural(long n, String one, String few, String many) {
        long d = n % 10, h = n % 100;
        return d == 1 && h != 11 ? one : d >= 2 && d <= 4 && (h < 12 || h > 14) ? few : many;
    }

    /** «3 позиции» */
    public String count(long n, String one, String few, String many) {
        return n + NB + plural(n, one, few, many);
    }

    public String win(Integer hour) {
        return hour == null ? "" : PickupSlotService.label(hour);
    }

    /** «октября» — родительный падеж месяца (1–12): «рабочих дней октября». */
    public static String monthGen(int month) {
        return MON[month - 1];
    }

    /** «8,4 млн» / «640 тыс» / «980» — для крупной цифры плитки. */
    public String shortMoney(BigDecimal v) {
        return v == null ? "—" : AdminWarehouseController.shortMoney(v.abs());
    }

    /** 15 окт */
    public String dm(LocalDate d) {
        return d == null ? "" : d.getDayOfMonth() + NB + MON3[d.getMonthValue() - 1];
    }

    /** 15 октября */
    public String dml(LocalDate d) {
        return d == null ? "" : d.getDayOfMonth() + NB + MON[d.getMonthValue() - 1];
    }

    /** 15.10.2026 */
    public String date(LocalDate d) {
        return d == null ? "" : d.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"));
    }

    public String date(LocalDateTime t) {
        return t == null ? "" : date(t.toLocalDate());
    }

    public String wday(LocalDate d) {
        return d == null ? "" : WD[d.getDayOfWeek().getValue() - 1];
    }

    public String wdayLong(LocalDate d) {
        return d == null ? "" : WDL[d.getDayOfWeek().getValue() - 1];
    }

    /** сегодня / завтра / вчера / 15 окт */
    public String relDay(LocalDate d) {
        if (d == null) return "";
        LocalDate today = LocalDate.now();
        if (d.equals(today)) return "сегодня";
        if (d.equals(today.plusDays(1))) return "завтра";
        if (d.equals(today.minusDays(1))) return "вчера";
        return dm(d) + (d.getYear() != today.getYear() ? " " + d.getYear() : "");
    }

    public String relDay(LocalDateTime t) {
        return t == null ? "" : relDay(t.toLocalDate());
    }

    /** вчера, 08:32 */
    public String when(LocalDateTime t) {
        return t == null ? "" : relDay(t.toLocalDate()) + ", " + t.format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    public String time(LocalDateTime t) {
        return t == null ? "" : t.format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    /** Время действия в таблице дня: «08:32», если в тот же день, иначе «12.10 08:32». */
    public String at(LocalDateTime t, LocalDate day) {
        if (t == null) return "";
        return t.toLocalDate().equals(day) ? time(t) : t.format(DateTimeFormatter.ofPattern("dd.MM")) + NB + time(t);
    }

    public String cap(String s) {
        return s == null || s.isEmpty() ? "" : s.substring(0, 1).toUpperCase(RU) + s.substring(1);
    }

    /** Вторник, 13 октября */
    /** Штук в возврате — сумма количеств по строкам. */
    public BigDecimal retPcs(com.confectionery.crm.domain.ProductReturn r) {
        return r.getItems().stream().map(com.confectionery.crm.domain.ProductReturnItem::getQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public String dayTitle(LocalDate d) {
        return cap(wdayLong(d)) + ", " + dml(d);
    }

    /** «+7 700 000 00 11» без разрыва строки. */
    public String phone(String p) {
        return p == null ? "" : p.replace(" ", NB);
    }

    /** 30 упаковок по 12 в коробке → «2 кор. + 6 шт»; без размера коробки — пусто. */
    public String boxText(BigDecimal qty, BigDecimal perBox) {
        if (qty == null || qty.signum() <= 0 || perBox == null || perBox.signum() <= 0) return "";
        BigDecimal[] qr = qty.divideAndRemainder(perBox);
        long full = qr[0].longValue();
        BigDecimal rest = qr[1];
        if (rest.signum() == 0) return full + NB + "кор.";
        return full > 0 ? full + NB + "кор. + " + num(rest) + NB + "шт" : "неполная коробка";
    }

    // заказ — для шаблонов без T(): день выдачи, источник, когда выдан, когда отменён
    public LocalDate day(com.confectionery.crm.domain.Order o) {
        return AdminOrders.day(o);
    }

    public String srcLong(com.confectionery.crm.domain.Order o) {
        return AdminOrders.srcLong(o);
    }

    public LocalDateTime doneAt(com.confectionery.crm.domain.Order o) {
        return AdminOrders.doneAt(o);
    }

    public LocalDateTime offAt(com.confectionery.crm.domain.Order o) {
        return AdminOrders.lastAt(o, com.confectionery.crm.domain.OrderStatus.CANCELLED);
    }

    public LocalDate parse(String isoDate) {
        return isoDate == null ? null : LocalDate.parse(isoDate);
    }

    public boolean isToday(String isoDate) {
        return LocalDate.now().toString().equals(isoDate);
    }

    public String abs(int v) {
        return String.valueOf(Math.abs(v));
    }

    /** Цена за единицу из суммы позиции: 12 000 ₸ за 25 кг → «480 ₸». */
    public String unitPrice(BigDecimal total, BigDecimal qty) {
        if (total == null || qty == null || qty.signum() <= 0) return "—";
        return money(total.divide(qty, 2, RoundingMode.HALF_UP));
    }

    /** Ячейка отчёта по типу колонки (ReportDto): MONEY, QTY, INT, TEXT. */
    public String cell(Object v, String type) {
        if (v == null) return "—";
        if (v instanceof Number n) {
            if ("MONEY".equals(type)) return money(n);
            return num(n);
        }
        return v.toString();
    }

    public LocalDate today() {
        return LocalDate.now();
    }
}
