package com.confectionery.crm.admin;

import org.springframework.ui.Model;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * Дни в списках «Заказы» и «Возвраты»: один день или промежуток с from по to включительно, и выбор их
 * календарём под заголовком (daypick.js с data-range). В адресе: date=today, tomorrow, 2026-10-21,
 * 2026-10-14..2026-10-21 или all — все даты.
 */
public record AdminSpan(LocalDate from, LocalDate to) {

    public boolean has(LocalDate d) {
        return !d.isBefore(from) && !d.isAfter(to);
    }

    /** null — all или непонятное: вызывающий сам решает, что подставить. */
    public static AdminSpan parse(String date, LocalDate today) {
        if (date.equals("today")) return new AdminSpan(today, today);
        if (date.equals("tomorrow")) return new AdminSpan(today.plusDays(1), today.plusDays(1));
        try {
            String[] p = date.split("\\.\\.", 2);
            LocalDate a = LocalDate.parse(p[0]), b = p.length > 1 ? LocalDate.parse(p[1]) : a;
            return a.isAfter(b) ? new AdminSpan(b, a) : new AdminSpan(a, b);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** Последние n дней по сегодня. */
    public static AdminSpan last(int days, LocalDate today) {
        return new AdminSpan(today.minusDays(days - 1L), today);
    }

    /** Для адреса: 2026-10-21 или 2026-10-14..2026-10-21. */
    public String key() {
        return from.equals(to) ? from.toString() : from + ".." + to;
    }

    /** «Сегодня, 21 октября», «Среда, 15 октября», «14 — 21 октября», «28 сентября — 4 октября». */
    public String label(AdminFormat fmt, LocalDate today) {
        if (from.equals(to)) {
            String rel = from.equals(today) ? "Сегодня" : from.equals(today.plusDays(1)) ? "Завтра"
                    : from.equals(today.minusDays(1)) ? "Вчера" : fmt.cap(fmt.wdayLong(from));
            return rel + ", " + fmt.dml(from) + (from.getYear() != today.getYear() ? " " + from.getYear() : "");
        }
        boolean years = from.getYear() != to.getYear() || to.getYear() != today.getYear();
        String a = from.getMonth() == to.getMonth() && !years ? String.valueOf(from.getDayOfMonth())
                : fmt.dml(from) + (years ? " " + from.getYear() : "");
        return a + " — " + fmt.dml(to) + (years ? " " + to.getYear() : "");
    }

    /** Коротко — для телефона, где в шапке мало места: «Сегодня», «15 окт», «14–21 окт», «28.09–4.10». */
    public String shortLabel(AdminFormat fmt) {
        if (from.equals(to)) return fmt.cap(fmt.relDay(from));
        if (from.getMonth() == to.getMonth() && from.getYear() == to.getYear()) return from.getDayOfMonth() + "–" + fmt.dm(to);
        return from.getDayOfMonth() + "." + String.format("%02d", from.getMonthValue()) + "–"
                + to.getDayOfMonth() + "." + String.format("%02d", to.getMonthValue());
    }

    /**
     * Дата под заголовком страницы и календарь: span null — «Все даты»; keep — остальные параметры адреса
     * без date (статус, поиск…), они сохраняются при выборе дня; min/max — какие дни можно выбрать.
     */
    public static void header(Model model, AdminFormat fmt, AdminSpan span, String base, String keep, LocalDate min, LocalDate max) {
        LocalDate today = LocalDate.now();
        model.addAttribute("sub", span == null ? "Все даты" : span.label(fmt, today));
        model.addAttribute("subShort", span == null ? "Все даты" : span.shortLabel(fmt));
        model.addAttribute("subPick", span == null ? "" : span.from().toString());
        model.addAttribute("subTo", span == null ? null : span.to().toString());
        model.addAttribute("subRange", "1");
        model.addAttribute("subBase", base);
        model.addAttribute("subParam", "date");
        model.addAttribute("subKeep", keep == null || keep.isEmpty() ? null : keep);
        model.addAttribute("dayMin", min.toString());
        model.addAttribute("dayMax", max.toString());
    }
}
