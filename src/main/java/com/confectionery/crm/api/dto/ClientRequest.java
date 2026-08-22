package com.confectionery.crm.api.dto;

import jakarta.validation.constraints.NotBlank;

public record ClientRequest(
        @NotBlank String name,
        String phone,
        String email,
        String address,
        String notes
) {
}
