package com.confectionery.crm.web;

import com.confectionery.crm.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    public String list(Model model) {
        model.addAttribute("activePage", "notifications");
        model.addAttribute("notifications", notificationService.recent());
        return "notifications/list";
    }
}
