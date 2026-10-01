package com.confectionery.crm.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record DashboardDto(
        LocalDate from,
        LocalDate to,
        long newOrders,
        long readyOrders,
        /** Фактическая продажа: отгружено минус возвраты за период. */
        BigDecimal todaySalesTotal,
        BigDecimal grossSalesTotal,
        BigDecimal returnsTotal,
        List<IngredientDto> lowStock,
        List<OrderNotificationGroupDto> orderNotifications,
        List<NotificationDto> generalNotifications,
        /** Оклады не в себестоимости за период (рабочие дни) — только ADMIN/ACCOUNTANT, иначе null. */
        SalaryCosts salaryCosts
) {
    /** sales — водители, грузчики, склад готовой продукции; admin — бухгалтерия, директор, оператор 1С, охрана. */
    public record SalaryCosts(BigDecimal sales, BigDecimal admin) {
    }
}
