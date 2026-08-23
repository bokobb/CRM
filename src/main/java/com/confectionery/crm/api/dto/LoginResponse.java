package com.confectionery.crm.api.dto;

import com.confectionery.crm.domain.User;

public record LoginResponse(
        String token,
        String username,
        String fullName,
        String role,
        String roleLabel
) {
    public static LoginResponse of(String token, User user) {
        return new LoginResponse(token, user.getUsername(), user.getFullName(),
                user.getRole().name(), user.getRole().getLabel());
    }
}
