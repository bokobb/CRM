package com.confectionery.crm.api.dto;

import jakarta.validation.constraints.NotBlank;

/** См. CounterpartyService.create — создание нового контрагента из мобильного приложения. */
public record CounterpartyCreateRequest(@NotBlank String name) {
}
