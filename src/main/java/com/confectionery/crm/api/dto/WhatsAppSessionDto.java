package com.confectionery.crm.api.dto;

/** Статус подключения WhatsApp-шлюза — см. настройки -> WhatsApp-сессия в приложении. */
public record WhatsAppSessionDto(String status, String phone) {
}
