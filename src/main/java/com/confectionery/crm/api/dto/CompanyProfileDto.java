package com.confectionery.crm.api.dto;

import com.confectionery.crm.domain.CompanyProfile;

/**
 * warehouseName/warehouseManagerName — только для чтения: шапка и подпись бланка «Объём и масса»,
 * который собирает приложение (склад — как в «Реализации» 1С, кладовщик — как в накладной).
 * pickupHours/about/legalInfo/instagramUrl/whatsappPhone/tiktokUrl/workHours/jobsPhone/mapPoint — для рекламной страницы на "/" (templates/promo.html).
 * foundedYear/externalLinks — для поисковиков (SeoService): год основания и карточки компании на 2ГИС, картах и т.п.;
 * старые версии приложения их не знают и шлют null — тогда не затираются, foundedYear = 0 — очистить.
 */
public record CompanyProfileDto(String name, String address, String phone, String email,
                                String warehouseName, String warehouseManagerName,
                                String pickupHours, String about, String legalInfo,
                                String instagramUrl, String whatsappPhone, String tiktokUrl,
                                String workHours, String jobsPhone, String mapPoint,
                                Integer foundedYear, String externalLinks) {
    public static CompanyProfileDto from(CompanyProfile p) {
        return new CompanyProfileDto(p.getName(), p.getAddress(), p.getPhone(), p.getEmail(), null, null,
                p.getPickupHours(), p.getAbout(), p.getLegalInfo(), p.getInstagramUrl(), p.getWhatsappPhone(), p.getTiktokUrl(),
                p.getWorkHours(), p.getJobsPhone(), p.getMapPoint(), p.getFoundedYear(), p.getExternalLinks());
    }

    public CompanyProfileDto withPrintInfo(String warehouse, String manager) {
        return new CompanyProfileDto(name, address, phone, email, warehouse, manager, pickupHours, about, legalInfo,
                instagramUrl, whatsappPhone, tiktokUrl, workHours, jobsPhone, mapPoint, foundedYear, externalLinks);
    }
}
