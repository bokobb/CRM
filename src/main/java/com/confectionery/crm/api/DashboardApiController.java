package com.confectionery.crm.api;

import com.confectionery.crm.api.dto.DashboardDto;
import com.confectionery.crm.api.dto.IngredientDto;
import com.confectionery.crm.domain.OrderStatus;
import com.confectionery.crm.repository.OrderRepository;
import com.confectionery.crm.service.IngredientService;
import com.confectionery.crm.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardApiController {

    private final OrderRepository orderRepository;
    private final IngredientService ingredientService;
    private final NotificationService notificationService;

    /** from/to не заданы — по умолчанию сегодня (в отличие от заказов/уведомлений, где "не задан" = все даты). */
    @GetMapping
    public DashboardDto get(@RequestParam(required = false) LocalDate from,
                             @RequestParam(required = false) LocalDate to) {
        LocalDate rangeFrom = from != null ? from : LocalDate.now();
        LocalDate rangeTo = to != null ? to : LocalDate.now();
        LocalDateTime start = rangeFrom.atStartOfDay();
        LocalDateTime end = rangeTo.plusDays(1).atStartOfDay();

        var grouped = notificationService.grouped(8, 8, start, end);
        return new DashboardDto(
                rangeFrom, rangeTo,
                orderRepository.countByStatusAndCreatedAtBetween(OrderStatus.NEW, start, end),
                orderRepository.countByStatusAndCreatedAtBetween(OrderStatus.READY, start, end),
                orderRepository.sumSalesBetween(start, end),
                ingredientService.findLowStock().stream().map(IngredientDto::from).toList(),
                grouped.orderGroups(),
                grouped.general()
        );
    }
}
