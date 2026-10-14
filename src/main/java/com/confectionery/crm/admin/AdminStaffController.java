package com.confectionery.crm.admin;

import com.confectionery.crm.domain.Employee;
import com.confectionery.crm.domain.EmployeeCostGroup;
import com.confectionery.crm.domain.Role;
import com.confectionery.crm.domain.User;
import com.confectionery.crm.service.*;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

import static com.confectionery.crm.admin.AdminSupport.*;

/**
 * Команда: сотрудники цеха и офиса (ставка, оклад, группа затрат, загрузка из 1С), табель по дням
 * месяца с выгрузкой в Excel, учётные записи для входа в админку и приложение (роль, пароль).
 * Ставки и оклады видят администратор и бухгалтер; учётки — тоже они. Свой пароль меняет каждый.
 */
@Controller
@RequiredArgsConstructor
public class AdminStaffController {

    private final EmployeeService employeeService;
    private final OneCEmployeeImportService importService;
    private final TimesheetService timesheetService;
    private final UserService userService;
    private final CurrentUserService currentUserService;
    private final PasswordEncoder passwordEncoder;

    private boolean money() {
        return currentUserService.hasRole(Role.ADMIN, Role.ACCOUNTANT);
    }

    @GetMapping("/admin/staff")
    @Transactional(readOnly = true)
    public String list(@RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "0") int inactive, Model model) {
        String nq = AdminOrderController.norm(q);
        List<Employee> rows = employeeService.findAll().stream()
                .filter(e -> inactive == 1 || e.isActive())
                .filter(e -> nq.isEmpty() || AdminOrderController.norm(e.getFullName() + " " + (e.getPosition() == null ? "" : e.getPosition())).contains(nq))
                .sorted(Comparator.comparing(Employee::getFullName, String.CASE_INSENSITIVE_ORDER)).toList();
        model.addAttribute("rows", rows);
        model.addAttribute("q", q);
        model.addAttribute("inactive", inactive == 1);
        model.addAttribute("money", money());
        model.addAttribute("today", LocalDate.now());
        model.addAttribute("canUsers", money());
        page(model, "Сотрудники", "цех, склад и офис; табель и входы в CRM", "staff");
        return "admin/staff/list";
    }

    @GetMapping({"/admin/staff/new", "/admin/staff/{id:\\d+}"})
    @Transactional(readOnly = true)
    public String form(@PathVariable(required = false) Long id, Model model) {
        Employee e = id == null ? new Employee() : employeeService.findAll().stream().filter(x -> x.getId().equals(id)).findFirst().orElseThrow();
        model.addAttribute("e", e);
        model.addAttribute("groups", EmployeeCostGroup.values());
        model.addAttribute("money", money());
        model.addAttribute("errors", List.of());
        page(model, "Сотрудники", "", "staff");
        return "admin/staff/form";
    }

    @PostMapping({"/admin/staff", "/admin/staff/{id:\\d+}"})
    public String save(@PathVariable(required = false) Long id, @RequestParam Map<String, String> f, RedirectAttributes ra) {
        try {
            String g = f.get("costGroup");
            Employee e = employeeService.save(id, f.getOrDefault("fullName", "").trim(), blank(f.get("position")), blank(f.get("phone")),
                    num(f.get("dailyRate")), id == null || "true".equals(f.get("active")), money(), num(f.get("monthlySalary")),
                    g == null || g.isBlank() ? null : EmployeeCostGroup.valueOf(g));
            ok(ra, "Сотрудник «" + e.getFullName() + "» сохранён");
            return "redirect:/admin/staff";
        } catch (RuntimeException e) {
            fail(ra, e);
            return id == null ? "redirect:/admin/staff/new" : "redirect:/admin/staff/" + id;
        }
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    @PostMapping("/admin/staff/import-1c")
    public String import1c(RedirectAttributes ra) {
        try {
            OneCEmployeeImportService.ImportResult r = importService.importAll();
            ok(ra, "Из 1С: всего " + r.total() + ", добавлено " + r.created() + ", обновлено " + r.updated());
        } catch (RuntimeException e) {
            fail(ra, e);
        }
        return "redirect:/admin/staff";
    }

    // ------------------------------------------------------------------ табель

    @GetMapping("/admin/staff/timesheet")
    @Transactional(readOnly = true)
    public String timesheet(@RequestParam(required = false) String month, Model model) {
        YearMonth ym = parse(month);
        model.addAttribute("s", timesheetService.month(ym, money()));
        model.addAttribute("ym", ym.toString());
        model.addAttribute("prev", ym.minusMonths(1).toString());
        model.addAttribute("next", ym.plusMonths(1).toString());
        model.addAttribute("money", money());
        page(model, "Сотрудники", "табель", "staff");
        return "admin/staff/timesheet";
    }

    private static YearMonth parse(String m) {
        try {
            return m == null || m.isBlank() ? YearMonth.now() : YearMonth.parse(m);
        } catch (Exception e) {
            return YearMonth.now();
        }
    }

    /** Изменённые клетки: поля c_{сотрудник}_{yyyy-mm-dd} = P / H / A / O / пусто. */
    @PostMapping("/admin/staff/timesheet")
    public String saveTimesheet(@RequestParam String month, HttpServletRequest req, RedirectAttributes ra) {
        YearMonth ym = parse(month);
        try {
            List<TimesheetService.CellInput> cells = new ArrayList<>();
            for (Map.Entry<String, String[]> e : req.getParameterMap().entrySet()) {
                if (!e.getKey().startsWith("c_")) continue;
                String[] parts = e.getKey().split("_");
                if (parts.length != 3) continue;
                String v = e.getValue().length == 0 ? "" : e.getValue()[0];
                cells.add(new TimesheetService.CellInput(Long.valueOf(parts[1]), LocalDate.parse(parts[2]), v.isBlank() ? null : v));
            }
            timesheetService.save(ym, cells, currentUserService.username().orElse("admin"));
            ok(ra, "Табель сохранён: " + cells.size() + " " + new AdminFormat().plural(cells.size(), "отметка", "отметки", "отметок"));
        } catch (RuntimeException e) {
            fail(ra, e);
        }
        return "redirect:/admin/staff/timesheet?month=" + ym;
    }

    @GetMapping("/admin/staff/timesheet.xlsx")
    public ResponseEntity<byte[]> xlsx(@RequestParam(required = false) String month) {
        YearMonth ym = parse(month);
        byte[] bytes = timesheetService.xlsx(ym, money());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("Табель_" + ym + ".xlsx", StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).body(bytes);
    }

    // ------------------------------------------------------------------ учётные записи

    @GetMapping("/admin/staff/users")
    @Transactional(readOnly = true)
    public String users(Model model, RedirectAttributes ra) {
        if (!money()) return "redirect:/admin/staff";
        List<User> rows = new ArrayList<>(userService.findAll());
        rows.sort(Comparator.comparing(User::getFullName, String.CASE_INSENSITIVE_ORDER));
        model.addAttribute("rows", rows);
        page(model, "Сотрудники", "входы в админку и приложение", "staff");
        return "admin/staff/users";
    }

    @GetMapping({"/admin/staff/users/new", "/admin/staff/users/{id:\\d+}"})
    @Transactional(readOnly = true)
    public String userForm(@PathVariable(required = false) Long id, Model model) {
        if (!money()) return "redirect:/admin/staff";
        model.addAttribute("u", id == null ? new User() : userService.findById(id));
        model.addAttribute("roles", Role.values());
        page(model, "Сотрудники", "", "staff");
        return "admin/staff/user";
    }

    @PostMapping({"/admin/staff/users", "/admin/staff/users/{id:\\d+}"})
    public String saveUser(@PathVariable(required = false) Long id, @RequestParam String username, @RequestParam String fullName,
                           @RequestParam Role role, @RequestParam(defaultValue = "false") boolean enabled,
                           @RequestParam(required = false) String password, RedirectAttributes ra) {
        try {
            if (!money()) throw new IllegalStateException("Учётные записи ведут администратор и бухгалтер");
            if (role == Role.ADMIN && !currentUserService.hasRole(Role.ADMIN)) throw new IllegalStateException("Роль администратора даёт только администратор");
            String pw = password == null || password.isBlank() ? null : password;
            if (pw != null && pw.length() < 6) throw new IllegalArgumentException("Пароль — не короче 6 знаков");
            if (id == null) {
                if (pw == null) throw new IllegalArgumentException("Задайте пароль");
                userService.create(username.trim(), pw, fullName.trim(), role);
            } else {
                User me = currentUserService.user().orElse(null);
                if (me != null && me.getId().equals(id) && (!enabled || role != me.getRole())) {
                    throw new IllegalStateException("Свою роль и доступ изменить нельзя — попросите другого администратора");
                }
                userService.update(id, username.trim(), fullName.trim(), role, enabled, pw);
            }
            ok(ra, "Учётная запись «" + username.trim() + "» сохранена");
            return "redirect:/admin/staff/users";
        } catch (RuntimeException e) {
            fail(ra, e);
            return id == null ? "redirect:/admin/staff/users/new" : "redirect:/admin/staff/users/" + id;
        }
    }

    // ------------------------------------------------------------------ свой пароль (любая роль)

    @GetMapping("/admin/me/password")
    public String passwordForm(Model model) {
        page(model, "Сменить пароль", "", "");
        return "admin/staff/password";
    }

    @PostMapping("/admin/me/password")
    public String password(@RequestParam String current, @RequestParam String password, @RequestParam String repeat,
                           RedirectAttributes ra) {
        try {
            User me = currentUserService.user().orElseThrow();
            if (!passwordEncoder.matches(current, me.getPasswordHash())) throw new IllegalArgumentException("Текущий пароль неверный");
            if (password.length() < 6) throw new IllegalArgumentException("Новый пароль — не короче 6 знаков");
            if (!password.equals(repeat)) throw new IllegalArgumentException("Пароли не совпадают");
            userService.update(me.getId(), me.getUsername(), me.getFullName(), me.getRole(), me.isEnabled(), password);
            ok(ra, "Пароль изменён");
            return "redirect:/admin";
        } catch (RuntimeException e) {
            fail(ra, e);
            return "redirect:/admin/me/password";
        }
    }
}
