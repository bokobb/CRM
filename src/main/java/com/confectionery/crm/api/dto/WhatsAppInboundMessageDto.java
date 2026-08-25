package com.confectionery.crm.api.dto;

import com.confectionery.crm.domain.WhatsAppInboundMessage;

import java.time.LocalDateTime;
import java.util.List;

public record WhatsAppInboundMessageDto(
        Long id,
        Long clientId,
        String clientName,
        String fromPhone,
        String rawText,
        String status,
        LocalDateTime createdAt,
        List<WhatsAppInboundItemDto> items
) {
    public static WhatsAppInboundMessageDto from(WhatsAppInboundMessage m) {
        return new WhatsAppInboundMessageDto(
                m.getId(), m.getClient().getId(), m.getClient().getName(), m.getFromPhone(),
                m.getRawText(), m.getStatus().name(), m.getCreatedAt(),
                m.getItems().stream().map(WhatsAppInboundItemDto::from).toList()
        );
    }
}
