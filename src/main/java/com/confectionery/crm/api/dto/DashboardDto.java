package com.confectionery.crm.api.dto;

import java.util.List;

public record DashboardDto(
        long newOrders,
        long inProduction,
        long readyOrders,
        int pendingTasks,
        List<IngredientDto> lowStock,
        List<NotificationDto> recentNotifications
) {
}
