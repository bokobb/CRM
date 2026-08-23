package com.confectionery.crm.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record DashboardDto(
        LocalDate from,
        LocalDate to,
        long newOrders,
        long readyOrders,
        BigDecimal todaySalesTotal,
        List<IngredientDto> lowStock,
        List<OrderNotificationGroupDto> orderNotifications,
        List<NotificationDto> generalNotifications
) {
}
