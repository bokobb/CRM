package com.confectionery.crm.api.dto;

import java.math.BigDecimal;

/**
 * ingredientId — необязательно: null означает "оставить прежнее сырьё, изменить только
 * количество". quantity — тоже необязательно, если задан ingredientId: null оставляет прежнее
 * количество (см. ProductionRequestService.substituteItem). Если оба поля null — запрос
 * отклоняется сервисом ("Укажите новое количество").
 */
public record ProductionRequestSubstituteRequest(Long ingredientId, BigDecimal quantity) {
}
