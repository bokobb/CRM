package com.confectionery.crm.api;

import com.confectionery.crm.api.dto.DashboardDto;
import com.confectionery.crm.api.dto.IngredientDto;
import com.confectionery.crm.api.dto.NotificationDto;
import com.confectionery.crm.domain.OrderStatus;
import com.confectionery.crm.domain.ProductionTaskStatus;
import com.confectionery.crm.repository.OrderRepository;
import com.confectionery.crm.service.IngredientService;
import com.confectionery.crm.service.NotificationService;
import com.confectionery.crm.service.ProductionTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardApiController {

    private final OrderRepository orderRepository;
    private final IngredientService ingredientService;
    private final NotificationService notificationService;
    private final ProductionTaskService productionTaskService;

    @GetMapping
    public DashboardDto get() {
        return new DashboardDto(
                orderRepository.countByStatus(OrderStatus.NEW),
                orderRepository.countByStatus(OrderStatus.IN_PRODUCTION),
                orderRepository.countByStatus(OrderStatus.READY),
                productionTaskService.findByStatus(ProductionTaskStatus.PENDING).size(),
                ingredientService.findLowStock().stream().map(IngredientDto::from).toList(),
                notificationService.recent().stream().limit(8).map(NotificationDto::from).toList()
        );
    }
}
