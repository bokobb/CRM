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
        var grouped = notificationService.grouped(50, 50);
        model.addAttribute("activePage", "notifications");
        model.addAttribute("orderGroups", grouped.orderGroups());
        model.addAttribute("general", grouped.general());
        return "notifications/list";
    }
}
