package com.confectionery.crm.api.dto;

import jakarta.validation.constraints.NotBlank;

public record PasswordResetRequest(@NotBlank String newPassword) {
}
