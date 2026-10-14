package com.confectionery.crm.admin;

import com.confectionery.crm.domain.OrderStatus;
import com.confectionery.crm.domain.ProductionRequestStatus;
import com.confectionery.crm.domain.Role;
import com.confectionery.crm.domain.User;
import com.confectionery.crm.repository.OrderRepository;
import com.confectionery.crm.repository.ProductionRequestRepository;
import com.confectionery.crm.service.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Общее для всех страниц админки: кто вошёл, меню по его роли, число новых заказов у пункта «Заказы». */
@ControllerAdvice(basePackageClasses = AdminModelAdvice.class)
@RequiredArgsConstructor
public class AdminModelAdvice {

    private final CurrentUserService currentUserService;
    private final OrderRepository orderRepository;
    private final ProductionRequestRepository requestRepository;
    private final AdminIcons icons;
    private java.util.Map<String, String> i20, i16, i15;

    public record Me(String name, String roleLabel, String initial, Role role) {
        public boolean is(String roleName) {
            return role != null && (role.name().equals(roleName) || role == Role.ADMIN);
        }

        public boolean isAdmin() {
            return role == Role.ADMIN;
        }
    }

    @ModelAttribute
    public void common(Model model, jakarta.servlet.http.HttpSession session) {
        // th:utext в Thymeleaf 3.1 не пускает к бинам — значки отдаются картами из модели: ${i['orders']}
        if (i20 == null) {
            i20 = icons.all(20);
            i16 = icons.all(16);
            i15 = icons.all(15);
        }
        model.addAttribute("i", i20);
        model.addAttribute("i16", i16);
        model.addAttribute("i15", i15);
        model.addAttribute("iconsJson", icons.json());
        model.addAttribute("today", java.time.LocalDate.now());
        Object denied = session.getAttribute("ADMIN_DENIED");
        if (denied != null) {
            session.removeAttribute("ADMIN_DENIED");
            model.addAttribute("toast", denied.toString());
        }
        User user = currentUserService.user().orElse(null);
        if (user == null) return;
        String name = user.getFullName() == null || user.getFullName().isBlank() ? user.getUsername() : user.getFullName();
        String roleLabel = user.getRole().getLabel().toLowerCase();   // как в приложении: «администратор», «бухгалтер»…
        model.addAttribute("me", new Me(name, roleLabel, name.substring(0, 1).toUpperCase(), user.getRole()));
        model.addAttribute("menu", AdminSections.menu(user.getRole()));
        model.addAttribute("navNew", orderRepository.countByStatus(OrderStatus.PLACED));
        // «Заявки на сырьё»: новые — ждут, когда склад сырья соберёт и выдаст
        model.addAttribute("navReq", requestRepository.countByStatus(ProductionRequestStatus.NEW));
    }
}
