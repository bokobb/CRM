package com.confectionery.crm.api;

import com.confectionery.crm.api.dto.DashboardDto;
import com.confectionery.crm.api.dto.IngredientDto;
import com.confectionery.crm.api.dto.ProductionDashboardDto;
import com.confectionery.crm.api.dto.ProductionRequestSummaryDto;
import com.confectionery.crm.domain.OrderStatus;
import com.confectionery.crm.repository.OrderRepository;
import com.confectionery.crm.repository.ProductReturnRepository;
import com.confectionery.crm.service.IngredientService;
import com.confectionery.crm.service.NotificationService;
import com.confectionery.crm.service.ProductionRequestService;
import com.confectionery.crm.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardApiController {

    private final OrderRepository orderRepository;
    private final ProductReturnRepository productReturnRepository;
    private final IngredientService ingredientService;
    private final NotificationService notificationService;
    private final UserService userService;
    private final ProductionRequestService productionRequestService;

    /** from/to не заданы — по умолчанию сегодня (в отличие от заказов/уведомлений, где "не задан" = все даты). */
    @GetMapping
    public DashboardDto get(@RequestParam(required = false) LocalDate from,
                             @RequestParam(required = false) LocalDate to,
                             Authentication authentication) {
        LocalDate rangeFrom = from != null ? from : LocalDate.now();
        LocalDate rangeTo = to != null ? to : LocalDate.now();
        LocalDateTime start = rangeFrom.atStartOfDay();
        LocalDateTime end = rangeTo.plusDays(1).atStartOfDay();

        var grouped = notificationService.grouped(8, 8, start, end, userService.roleOf(authentication.getName()));
        BigDecimal grossSales = orderRepository.sumSalesBetween(start, end);
        BigDecimal returns = productReturnRepository.sumForSalesBetween(start, end);
        return new DashboardDto(
                rangeFrom, rangeTo,
                orderRepository.countByStatusAndCreatedAtBetween(OrderStatus.NEW, start, end),
                orderRepository.countByStatusAndCreatedAtBetween(OrderStatus.READY, start, end),
                grossSales.subtract(returns),
                grossSales,
                returns,
                ingredientService.findLowStock().stream().map(IngredientDto::from).toList(),
                grouped.orderGroups(),
                grouped.general()
        );
    }

    /** Дашборд для роли WAREHOUSE_RAW — про заявки на сырьё, не про заказы клиентов. */
    @GetMapping("/production")
    public ProductionDashboardDto getProduction(@RequestParam(required = false) LocalDate from,
                                                 @RequestParam(required = false) LocalDate to) {
        LocalDate rangeFrom = from != null ? from : LocalDate.now();
        LocalDate rangeTo = to != null ? to : LocalDate.now();
        LocalDateTime start = rangeFrom.atStartOfDay();
        LocalDateTime end = rangeTo.plusDays(1).atStartOfDay();

        var summary = productionRequestService.dashboardSummary(start, end, 10);
        return new ProductionDashboardDto(
                rangeFrom, rangeTo,
                summary.counts().pending(), summary.counts().awaitingReceipt(),
                summary.counts().createdInRange(), summary.counts().collectedInRange(),
                ingredientService.findLowStock().stream().map(IngredientDto::from).toList(),
                summary.pendingRequests().stream().map(ProductionRequestSummaryDto::from).toList()
        );
    }
}
