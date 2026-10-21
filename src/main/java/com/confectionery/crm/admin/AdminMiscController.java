package com.confectionery.crm.admin;

import com.confectionery.crm.domain.Role;
import com.confectionery.crm.domain.Vacancy;
import com.confectionery.crm.domain.VacancyReply;
import com.confectionery.crm.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.*;

import static com.confectionery.crm.admin.AdminSupport.*;

/**
 * Вакансии (страница /vacancies на сайте и отклики). Отчёты — AdminReportController, настройки — AdminSettingsController.
 */
@Controller
@RequiredArgsConstructor
public class AdminMiscController {

    private final VacancyService vacancyService;
    private final VacancyReplyService replyService;
    private final CurrentUserService currentUserService;

    // ------------------------------------------------------------------ вакансии

    static final Map<String, String> REPLY_STATUS = new LinkedHashMap<>();

    static {
        REPLY_STATUS.put(VacancyReply.NEW, "Новый");
        REPLY_STATUS.put(VacancyReply.CONTACTED, "Связались");
        REPLY_STATUS.put(VacancyReply.INTERVIEW, "Собеседование");
        REPLY_STATUS.put(VacancyReply.HIRED, "Принят на работу");
        REPLY_STATUS.put(VacancyReply.REJECTED, "Отказ");
    }

    @GetMapping("/admin/jobs")
    @Transactional(readOnly = true)
    public String jobs(@RequestParam(defaultValue = "vacancies") String tab, Model model) {
        List<Vacancy> vacancies = new ArrayList<>(vacancyService.findAll());
        vacancies.sort(Comparator.comparingInt(Vacancy::getSortOrder));
        model.addAttribute("vacancies", vacancies);
        model.addAttribute("replies", replyService.findAll());
        model.addAttribute("newReplies", replyService.findAll().stream().filter(r -> VacancyReply.NEW.equals(r.getStatus())).count());
        model.addAttribute("tab", tab.equals("replies") ? "replies" : "vacancies");
        model.addAttribute("statuses", REPLY_STATUS);
        page(model, "Вакансии", "страница «Вакансии» на сайте и отклики", "jobs");
        return "admin/jobs/list";
    }

    @GetMapping({"/admin/jobs/new", "/admin/jobs/{id:\\d+}"})
    @Transactional(readOnly = true)
    public String jobForm(@PathVariable(required = false) Long id, Model model) {
        return jobForm(id == null ? new Vacancy() : vacancyService.get(id), null, model);
    }

    private String jobForm(Vacancy v, String error, Model model) {
        model.addAttribute("v", v);
        model.addAttribute("error", error);
        page(model, "Вакансии", v.getId() == null ? "новая вакансия" : v.getTitle(), "jobs");
        return "admin/jobs/form";
    }

    @PostMapping({"/admin/jobs", "/admin/jobs/{id:\\d+}"})
    public String saveJob(@PathVariable(required = false) Long id, @RequestParam Map<String, String> f, Model model, RedirectAttributes ra) {
        boolean active = "true".equals(f.get("active"));
        try {
            Vacancy v = vacancyService.save(id, new VacancyService.VacancyFields(t(f, "title"), t(f, "summary"), t(f, "schedule"),
                    t(f, "experience"), t(f, "salary"), t(f, "duties"), t(f, "requirements"), t(f, "conditions"), active));
            ok(ra, "Вакансия «" + v.getTitle() + "» сохранена" + (v.isActive() ? " и показана на сайте" : ", на сайте не показана"));
            return "redirect:/admin/jobs";
        } catch (RuntimeException e) {
            // показываем форму снова с тем, что ввели, а не пустую
            Vacancy v = new Vacancy();
            v.setId(id);
            v.setTitle(t(f, "title"));
            v.setSummary(t(f, "summary"));
            v.setSchedule(t(f, "schedule"));
            v.setExperience(t(f, "experience"));
            v.setSalary(t(f, "salary"));
            v.setDuties(t(f, "duties"));
            v.setRequirements(t(f, "requirements"));
            v.setConditions(t(f, "conditions"));
            v.setActive(active);
            return jobForm(v, e.getMessage(), model);
        }
    }

    private static String t(Map<String, String> f, String k) {
        String v = f.get(k);
        return v == null || v.isBlank() ? null : v.trim();
    }

    @PostMapping("/admin/jobs/{id:\\d+}/active")
    public String jobActive(@PathVariable Long id, @RequestParam boolean active, RedirectAttributes ra) {
        try {
            vacancyService.setActive(id, active);
            ok(ra, active ? "Вакансия снова на сайте" : "Вакансия снята с сайта");
        } catch (RuntimeException e) {
            fail(ra, e);
        }
        return "redirect:/admin/jobs";
    }

    @PostMapping("/admin/jobs/{id:\\d+}/move")
    public String jobMove(@PathVariable Long id, @RequestParam String dir, RedirectAttributes ra) {
        try {
            List<Vacancy> all = new ArrayList<>(vacancyService.findAll());
            all.sort(Comparator.comparingInt(Vacancy::getSortOrder));
            List<Long> ids = new ArrayList<>(all.stream().map(Vacancy::getId).toList());
            int i = ids.indexOf(id);
            if (i >= 0) {
                ids.remove(i);
                ids.add(dir.equals("up") ? Math.max(0, i - 1) : Math.min(ids.size(), i + 1), id);
                vacancyService.reorder(ids);
            }
        } catch (RuntimeException e) {
            fail(ra, e);
        }
        return "redirect:/admin/jobs";
    }

    @PostMapping("/admin/jobs/{id:\\d+}/delete")
    public String jobDelete(@PathVariable Long id, RedirectAttributes ra) {
        try {
            vacancyService.delete(id);
            ok(ra, "Вакансия удалена");
        } catch (RuntimeException e) {
            fail(ra, e);
        }
        return "redirect:/admin/jobs";
    }

    @PostMapping("/admin/jobs/replies/{id}")
    public String reply(@PathVariable Long id, @RequestParam String status, @RequestParam(required = false) String note, RedirectAttributes ra) {
        try {
            replyService.update(id, status, note);
            ok(ra, "Отклик обновлён");
        } catch (RuntimeException e) {
            fail(ra, e);
        }
        return "redirect:/admin/jobs?tab=replies";
    }
}
