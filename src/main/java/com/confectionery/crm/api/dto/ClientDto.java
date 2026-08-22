package com.confectionery.crm.api.dto;

import com.confectionery.crm.domain.Client;

import java.time.LocalDateTime;

public record ClientDto(
        Long id,
        String name,
        String phone,
        String email,
        String address,
        String notes,
        LocalDateTime createdAt
) {
    public static ClientDto from(Client c) {
        return new ClientDto(c.getId(), c.getName(), c.getPhone(), c.getEmail(), c.getAddress(), c.getNotes(), c.getCreatedAt());
    }
}
