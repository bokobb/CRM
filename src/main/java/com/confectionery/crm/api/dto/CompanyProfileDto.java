package com.confectionery.crm.api.dto;

import com.confectionery.crm.domain.CompanyProfile;

public record CompanyProfileDto(String name, String address, String phone, String email) {
    public static CompanyProfileDto from(CompanyProfile p) {
        return new CompanyProfileDto(p.getName(), p.getAddress(), p.getPhone(), p.getEmail());
    }
}
