package com.confectionery.crm.api.dto;

import java.time.LocalDateTime;
import java.util.List;

/** Группировка уведомлений по заказу для дашборда: одна строка на заказ, история — по нажатию. */
public record OrderNotificationGroupDto(
        Long orderId,
        String clientName,
        String status,
        String statusLabel,
        LocalDateTime lastNotifiedAt,
        List<NotificationDto> history
) {
}
