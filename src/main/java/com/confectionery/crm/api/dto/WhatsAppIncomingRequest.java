package com.confectionery.crm.api.dto;

import jakarta.validation.constraints.NotBlank;

/** Тело вебхука от Node.js-шлюза WhatsApp (см. whatsapp-gateway/index.js) на входящее сообщение. */
public record WhatsAppIncomingRequest(
        @NotBlank String from,
        @NotBlank String text,
        String name
) {
}
