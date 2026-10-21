package com.confectionery.crm.admin;

import org.springframework.ui.Model;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Общее для контроллеров разделов: заголовок страницы и сообщения во всплывающей строке. */
public final class AdminSupport {

    private AdminSupport() {
    }

    public static void page(Model model, String title, String sub, String sec) {
        model.addAttribute("title", title);
        model.addAttribute("sub", sub == null ? "" : sub);
        model.addAttribute("sec", sec);
    }

    public static void ok(RedirectAttributes ra, String text) {
        ra.addFlashAttribute("toast", text);
    }

    public static void fail(RedirectAttributes ra, Exception e) {
        ra.addFlashAttribute("toast", e.getMessage() == null ? "Не получилось — попробуйте ещё раз" : e.getMessage());
        ra.addFlashAttribute("toastError", true);
    }

    /** «12,5» / «12.5» / «1 200» → число; пусто или не число — null. */
    public static BigDecimal num(String s) {
        if (s == null) return null;
        String v = s.replace(" ", "").replace(" ", "").replace(',', '.').trim();
        if (v.isEmpty()) return null;
        try {
            return new BigDecimal(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static Long id(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Long.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static LocalDate date(String s, LocalDate fallback) {
        if (s == null || s.isBlank()) return fallback;
        try {
            return LocalDate.parse(s.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    public static String[] arr(jakarta.servlet.http.HttpServletRequest req, String name) {
        String[] v = req.getParameterValues(name);
        return v == null ? new String[0] : v;
    }

    public static String at(String[] a, int i) {
        return i < a.length ? a[i] : "";
    }

    /** Куда вернуться после действия: в «Настройки», если пришли оттуда (next), иначе — в свой раздел. */
    public static String back(String next, String fallback) {
        return "redirect:" + (next != null && next.matches("/admin/settings(#[\\w-]+)?") ? next : fallback);
    }
}
