package com.confectionery.crm.web;

import com.confectionery.crm.domain.OrderStatus;
import com.confectionery.crm.domain.ProductionTaskStatus;
import com.confectionery.crm.repository.OrderRepository;
import com.confectionery.crm.service.IngredientService;
import com.confectionery.crm.service.NotificationService;
import com.confectionery.crm.service.ProductionTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@RequiredArgsConstructor
public class DashboardController {

    private final OrderRepository orderRepository;
    private final IngredientService ingredientService;
    private final NotificationService notificationService;
    private final ProductionTaskService productionTaskService;

    @GetMapping("/")
    public String dashboard(Model model) {
        model.addAttribute("activePage", "dashboard");
        model.addAttribute("newOrders", orderRepository.countByStatus(OrderStatus.NEW));
        model.addAttribute("inProduction", orderRepository.countByStatus(OrderStatus.IN_PRODUCTION));
        model.addAttribute("readyOrders", orderRepository.countByStatus(OrderStatus.READY));
        model.addAttribute("pendingTasks", productionTaskService.findByStatus(ProductionTaskStatus.PENDING).size());
        model.addAttribute("lowStock", ingredientService.findLowStock());
        model.addAttribute("recentNotifications", notificationService.recent().stream().limit(8).toList());
        return "dashboard";
    }
}
