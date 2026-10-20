package com.confectionery.crm.service;

import com.confectionery.crm.domain.CompanyProfile;
import com.confectionery.crm.repository.CompanyProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Year;

@Service
@RequiredArgsConstructor
public class CompanyProfileService {

    private final CompanyProfileRepository companyProfileRepository;

    public CompanyProfile get() {
        return companyProfileRepository.findById(1L).orElseGet(CompanyProfile::new);
    }

    public CompanyProfile save(String name, String address, String phone, String email,
                               String pickupHours, String about, String legalInfo,
                               String instagramUrl, String whatsappPhone, String tiktokUrl,
                               String workHours, String jobsPhone, String mapPoint,
                               Integer foundedYear, String externalLinks) {
        CompanyProfile profile = get();
        profile.setName(name);
        profile.setAddress(address);
        profile.setPhone(phone);
        profile.setEmail(email);
        profile.setPickupHours(pickupHours);
        profile.setAbout(about);
        profile.setLegalInfo(legalInfo);
        // Старые версии приложения этих полей не знают и шлют null — тогда не затираем.
        if (instagramUrl != null) profile.setInstagramUrl(instagramUrl);
        if (whatsappPhone != null) profile.setWhatsappPhone(whatsappPhone);
        if (tiktokUrl != null) profile.setTiktokUrl(tiktokUrl);
        if (workHours != null) profile.setWorkHours(workHours);
        if (jobsPhone != null) profile.setJobsPhone(jobsPhone);
        if (mapPoint != null) profile.setMapPoint(mapPoint);
        if (foundedYear != null) {
            if (foundedYear != 0 && (foundedYear < 1900 || foundedYear > Year.now().getValue())) {
                throw new IllegalArgumentException("Год основания — четыре цифры, например 2018");
            }
            profile.setFoundedYear(foundedYear == 0 ? null : foundedYear);
        }
        if (externalLinks != null) profile.setExternalLinks(externalLinks);
        return companyProfileRepository.save(profile);
    }

    /** Настройки › Магазин: выбор дня/времени выдачи и способа оплаты на /shop. */
    public CompanyProfile saveShopOptions(boolean pickupChoice, boolean paymentChoice) {
        CompanyProfile profile = get();
        profile.setShopPickupChoice(pickupChoice);
        profile.setShopPaymentChoice(paymentChoice);
        return companyProfileRepository.save(profile);
    }
}
