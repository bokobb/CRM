package com.confectionery.crm.admin;

import com.confectionery.crm.domain.Role;
import com.confectionery.crm.service.CurrentUserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Права по разделам: /admin/{раздел}/… открывается только ролям, которым раздел виден в меню
 * (AdminSections). Остальным — на их первый раздел с сообщением внизу.
 */
@Component
@RequiredArgsConstructor
public class AdminAccessInterceptor implements HandlerInterceptor {

    private final CurrentUserService currentUserService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String path = request.getServletPath();
        if (!path.startsWith("/admin/")) return true;
        String[] parts = path.substring("/admin/".length()).split("/");
        String key = parts.length > 0 ? parts[0] : "";
        if (key.equals("api") && parts.length > 1) {
            key = switch (parts[1]) {
                case "slots", "clients" -> "orders";
                case "products" -> "products";
                case "ingredients" -> "materials";
                default -> "";
            };
        }
        AdminSections.Section section = AdminSections.byKey(key);
        if (section == null) return true;
        Role role = currentUserService.role().orElse(null);
        if (role == null) return true; // вход проверяет Spring Security
        if (section.visibleTo(role)) return true;
        request.getSession().setAttribute("ADMIN_DENIED", "Раздел «" + section.title() + "» вам недоступен.");
        response.sendRedirect(request.getContextPath() + AdminSections.home(role).href());
        return false;
    }
}
