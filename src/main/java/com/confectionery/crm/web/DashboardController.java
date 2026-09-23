package com.confectionery.crm.web;

import com.confectionery.crm.api.dto.ProductionRequestSummaryDto;
import com.confectionery.crm.domain.OrderStatus;
import com.confectionery.crm.domain.ProductionTaskStatus;
import com.confectionery.crm.repository.OrderRepository;
import com.confectionery.crm.repository.ProductReturnRepository;
import com.confectionery.crm.service.IngredientService;
import com.confectionery.crm.service.NotificationService;
import com.confectionery.crm.service.ProductionRequestService;
import com.confectionery.crm.service.ProductionTaskService;
import com.confectionery.crm.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Controller
@RequiredArgsConstructor
public class DashboardController {

    private final OrderRepository orderRepository;
    private final ProductReturnRepository productReturnRepository;
    private final IngredientService ingredientService;
    private final NotificationService notificationService;
    private final ProductionTaskService productionTaskService;
    private final ProductionRequestService productionRequestService;
    private final UserService userService;

    /** from/to не заданы — по умолчанию сегодня (совпадает с поведением мобильного дашборда). */
    @GetMapping("/")
    public String dashboard(@RequestParam(required = false) LocalDate from,
                             @RequestParam(required = false) LocalDate to,
                             Model model,
                             Authentication authentication) {
        LocalDate rangeFrom = from != null ? from : LocalDate.now();
        LocalDate rangeTo = to != null ? to : LocalDate.now();
        LocalDateTime start = rangeFrom.atStartOfDay();
        LocalDateTime end = rangeTo.plusDays(1).atStartOfDay();

        model.addAttribute("activePage", "dashboard");
        model.addAttribute("rangeFrom", rangeFrom);
        model.addAttribute("rangeTo", rangeTo);

        if ("WAREHOUSE_RAW".equals(userService.roleOf(authentication.getName()))) {
            var summary = productionRequestService.dashboardSummary(start, end, 10);
            model.addAttribute("counts", summary.counts());
            model.addAttribute("pendingRequests", summary.pendingRequests().stream().map(ProductionRequestSummaryDto::from).toList());
            model.addAttribute("lowStock", ingredientService.findLowStock());
            return "dashboard-production";
        }

        model.addAttribute("newOrders", orderRepository.countByStatusAndCreatedAtBetween(OrderStatus.NEW, start, end));
        model.addAttribute("inProduction", orderRepository.countByStatus(OrderStatus.IN_PRODUCTION));
        model.addAttribute("readyOrders", orderRepository.countByStatusAndCreatedAtBetween(OrderStatus.READY, start, end));
        model.addAttribute("pendingTasks", productionTaskService.findByStatus(ProductionTaskStatus.PENDING).size());
        // Фактическая продажа — за вычетом возвратов за тот же период (как в мобильном дашборде).
        model.addAttribute("salesTotal", orderRepository.sumSalesBetween(start, end)
                .subtract(productReturnRepository.sumBetween(rangeFrom, rangeTo)));
        model.addAttribute("lowStock", ingredientService.findLowStock());
        model.addAttribute("orderGroups",
                notificationService.grouped(10, 10, start, end, userService.roleOf(authentication.getName())).orderGroups());
        return "dashboard";
    }
}
