package com.confectionery.crm.admin;

import com.confectionery.crm.api.dto.ReportDto;
import com.confectionery.crm.api.dto.WhatsAppSessionDto;
import com.confectionery.crm.domain.CompanyProfile;
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
 * Вакансии (страница /vacancies на сайте и отклики), отчёты (те же, что в приложении — по роли) и
 * настройки (реквизиты и контакты для сайта и накладных, окна выдачи, WhatsApp).
 */
@Controller
@RequiredArgsConstructor
public class AdminMiscController {

    private final VacancyService vacancyService;
    private final VacancyReplyService replyService;
    private final ReportService reportService;
    private final CompanyProfileService profileService;
    private final PickupSlotService pickupSlotService;
    private final WhatsAppGatewayClient whatsAppGatewayClient;
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

    // ------------------------------------------------------------------ отчёты

    @GetMapping("/admin/reports")
    public String reports(Model model) {
        String role = currentUserService.role().map(Enum::name).orElse("");
        model.addAttribute("kinds", ReportService.available(role).stream().map(ReportService.Kind::info).toList());
        model.addAttribute("canTimesheet", currentUserService.hasRole(Role.ADMIN, Role.ACCOUNTANT, Role.TECHNOLOGIST));
        page(model, "Отчёты", "продажи, производство, закупки, сотрудники", "reports");
        return "admin/reports/list";
    }

    @GetMapping("/admin/reports/{key}")
    @Transactional(readOnly = true)
    public String report(@PathVariable String key, @RequestParam(required = false) String from, @RequestParam(required = false) String to,
                         Model model, RedirectAttributes ra) {
        String role = currentUserService.role().map(Enum::name).orElse("");
        ReportService.Kind kind;
        try {
            kind = ReportService.Kind.of(key);
        } catch (IllegalArgumentException e) {
            fail(ra, e);
            return "redirect:/admin/reports";
        }
        if (!ReportService.available(role).contains(kind)) {
            fail(ra, new IllegalStateException("Этот отчёт вам недоступен"));
            return "redirect:/admin/reports";
        }
        if (kind == ReportService.Kind.EMPLOYEES) return "redirect:/admin/staff/timesheet";
        ReportDto.Info info = kind.info();
        LocalDate today = LocalDate.now();
        boolean day = "DAY".equals(info.period());
        LocalDate f = AdminSupport.date(from, day || "TODAY".equals(info.defaultRange()) ? today : today.withDayOfMonth(1));
        LocalDate t = day ? f : AdminSupport.date(to, today);
        try {
            model.addAttribute("r", reportService.build(kind, f, t, currentUserService.hasRole(Role.ADMIN, Role.ACCOUNTANT)));
        } catch (RuntimeException e) {
            model.addAttribute("error", e.getMessage());
        }
        model.addAttribute("info", info);
        model.addAttribute("day", day);
        model.addAttribute("from", f);
        model.addAttribute("to", t);
        page(model, "Отчёты", info.title(), "reports");
        return "admin/reports/view";
    }

    // ------------------------------------------------------------------ настройки

    @GetMapping("/admin/settings")
    @Transactional(readOnly = true)
    public String settings(Model model) {
        model.addAttribute("p", profileService.get());
        model.addAttribute("firstHour", pickupSlotService.firstHour());
        model.addAttribute("lastHour", pickupSlotService.lastHour());
        model.addAttribute("capacity", pickupSlotService.capacity());
        WhatsAppSessionDto wa;
        try {
            wa = whatsAppGatewayClient.getStatus();
        } catch (RuntimeException e) {
            wa = null;
        }
        model.addAttribute("wa", wa);
        page(model, "Настройки", "реквизиты, контакты сайта, окна выдачи", "settings");
        return "admin/settings";
    }

    @PostMapping("/admin/settings/profile")
    public String saveProfile(@RequestParam Map<String, String> f, RedirectAttributes ra) {
        try {
            if (!currentUserService.hasRole(Role.ADMIN, Role.ACCOUNTANT)) throw new IllegalStateException("Реквизиты меняет директор или бухгалтер");
            CompanyProfile p = profileService.save(t(f, "name"), t(f, "address"), t(f, "phone"), t(f, "email"), t(f, "pickupHours"),
                    t(f, "about"), t(f, "legalInfo"), t(f, "instagramUrl"), t(f, "whatsappPhone"), t(f, "tiktokUrl"),
                    t(f, "workHours"), t(f, "jobsPhone"), t(f, "mapPoint"));
            ok(ra, "Настройки «" + (p.getName() == null ? "компании" : p.getName()) + "» сохранены");
        } catch (RuntimeException e) {
            fail(ra, e);
        }
        return "redirect:/admin/settings";
    }

    @PostMapping("/admin/settings/shop")
    public String saveShop(@RequestParam(required = false) String pickupChoice,
                           @RequestParam(required = false) String paymentChoice, RedirectAttributes ra) {
        try {
            if (!currentUserService.hasRole(Role.ADMIN)) throw new IllegalStateException("Настройки магазина меняет директор");
            CompanyProfile p = profileService.saveShopOptions(pickupChoice != null, paymentChoice != null);
            ok(ra, "Магазин: выбор времени выдачи " + (p.pickupChoiceOn() ? "включён" : "выключен")
                    + ", выбор оплаты " + (p.paymentChoiceOn() ? "включён" : "выключен"));
        } catch (RuntimeException e) {
            fail(ra, e);
        }
        return "redirect:/admin/settings";
    }
}
