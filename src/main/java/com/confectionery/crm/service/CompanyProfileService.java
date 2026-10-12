package com.confectionery.crm.service;

import com.confectionery.crm.domain.CompanyProfile;
import com.confectionery.crm.repository.CompanyProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CompanyProfileService {

    private final CompanyProfileRepository companyProfileRepository;

    public CompanyProfile get() {
        return companyProfileRepository.findById(1L).orElseGet(CompanyProfile::new);
    }

    public CompanyProfile save(String name, String address, String phone, String email,
                               String pickupHours, String about, String legalInfo,
                               String instagramUrl, String whatsappPhone) {
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
        return companyProfileRepository.save(profile);
    }
}
