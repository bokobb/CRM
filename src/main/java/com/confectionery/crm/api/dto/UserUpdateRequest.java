package com.confectionery.crm.api.dto;

import com.confectionery.crm.domain.Role;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record UserUpdateRequest(
        @NotBlank String fullName,
        @NotNull Role role,
        boolean enabled
) {
}
