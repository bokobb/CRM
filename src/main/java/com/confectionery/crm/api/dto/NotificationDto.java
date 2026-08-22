package com.confectionery.crm.api.dto;

import com.confectionery.crm.domain.Notification;

import java.time.LocalDateTime;

public record NotificationDto(
        Long id,
        String recipient,
        String message,
        String channel,
        Long orderId,
        boolean sent,
        LocalDateTime createdAt
) {
    public static NotificationDto from(Notification n) {
        return new NotificationDto(n.getId(), n.getRecipient(), n.getMessage(), n.getChannel(),
                n.getOrder() != null ? n.getOrder().getId() : null, n.isSent(), n.getCreatedAt());
    }
}
