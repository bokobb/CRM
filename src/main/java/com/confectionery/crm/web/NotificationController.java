package com.confectionery.crm.web;

import com.confectionery.crm.service.NotificationService;
import com.confectionery.crm.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;
    private final UserService userService;

    @GetMapping
    public String list(Model model, Authentication authentication) {
        var grouped = notificationService.grouped(50, 50, userService.roleOf(authentication.getName()));
        model.addAttribute("activePage", "notifications");
        model.addAttribute("orderGroups", grouped.orderGroups());
        model.addAttribute("general", grouped.general());
        return "notifications/list";
    }
}
