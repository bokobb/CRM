package com.confectionery.crm.api.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record DebtRequest(@NotNull LocalDate dueDate) {
}
