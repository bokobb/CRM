package com.confectionery.crm.api.dto;

import com.confectionery.crm.domain.User;

import java.time.LocalDateTime;

public record UserDto(
        Long id,
        String username,
        String fullName,
        String role,
        String roleLabel,
        boolean enabled,
        LocalDateTime createdAt
) {
    public static UserDto from(User u) {
        return new UserDto(u.getId(), u.getUsername(), u.getFullName(),
                u.getRole().name(), u.getRole().getLabel(), u.isEnabled(), u.getCreatedAt());
    }
}
