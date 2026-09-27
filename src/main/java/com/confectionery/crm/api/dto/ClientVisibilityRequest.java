package com.confectionery.crm.api.dto;

import java.time.LocalDate;

/** Скрыть товар от клиентов: hidden=false — вернуть; until — по какую дату включительно (null — бессрочно). */
public record ClientVisibilityRequest(boolean hidden, LocalDate until) {
}
