package com.confectionery.crm.admin;

import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Вход в админку: /admin/login. Сам вход и выход — Spring Security (см. SecurityConfig.adminFilterChain). */
@Controller
public class AdminLoginController {

    @GetMapping("/admin/login")
    public String login(@RequestParam(required = false) String error, @RequestParam(required = false) Long locked,
                        @RequestParam(required = false) String logout, HttpSession session,
                        Authentication authentication, Model model) {
        if (authentication != null && authentication.isAuthenticated() && !(authentication instanceof AnonymousAuthenticationToken)) {
            return "redirect:/admin";
        }
        if (locked != null) {
            model.addAttribute("error", "Слишком много неудачных попыток. Попробуйте снова через " + locked + " сек.");
        } else if (error != null) {
            model.addAttribute("error", "Неверный логин или пароль.");
        }
        model.addAttribute("logout", logout != null);
        Object user = session.getAttribute("ADMIN_LOGIN_USER");
        model.addAttribute("username", user == null ? "" : user.toString());
        return "admin/login";
    }

    /** Старый адрес входа — на новый. */
    @GetMapping("/login")
    public String oldLogin() {
        return "redirect:/admin/login";
    }
}
