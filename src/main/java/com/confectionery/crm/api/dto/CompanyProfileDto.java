package com.confectionery.crm.api.dto;

import com.confectionery.crm.domain.CompanyProfile;

/**
 * warehouseName/warehouseManagerName — только для чтения: шапка и подпись бланка «Объём и масса»,
 * который собирает приложение (склад — как в «Реализации» 1С, кладовщик — как в накладной).
 */
public record CompanyProfileDto(String name, String address, String phone, String email,
                                String warehouseName, String warehouseManagerName) {
    public static CompanyProfileDto from(CompanyProfile p) {
        return new CompanyProfileDto(p.getName(), p.getAddress(), p.getPhone(), p.getEmail(), null, null);
    }

    public CompanyProfileDto withPrintInfo(String warehouse, String manager) {
        return new CompanyProfileDto(name, address, phone, email, warehouse, manager);
    }
}
