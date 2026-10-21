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

    /** Админка › Настройки › Компания: название, юр. лицо и БИН, адрес, телефоны, почта; пустое — null. */
    public CompanyProfile saveCompany(String name, String legalInfo, String address, String phone, String email) {
        CompanyProfile profile = get();
        profile.setName(blank(name));
        profile.setLegalInfo(blank(legalInfo));
        profile.setAddress(blank(address));
        profile.setPhone(blank(phone));
        profile.setEmail(blank(email));
        return companyProfileRepository.save(profile);
    }

    /** Админка › Настройки › Сайт: тексты и контакты страниц для покупателей; пустое — null (на сайте этого нет). */
    public CompanyProfile saveSite(String about, String workHours, String pickupHours, String whatsappPhone, String jobsPhone,
                                   String instagramUrl, String tiktokUrl, String mapPoint,
                                   Integer foundedYear, String externalLinks) {
        if (foundedYear != null && (foundedYear < 1900 || foundedYear > Year.now().getValue())) {
            throw new IllegalArgumentException("Год основания — четыре цифры, например 2018.");
        }
        CompanyProfile profile = get();
        profile.setAbout(blank(about));
        profile.setWorkHours(blank(workHours));
        profile.setPickupHours(blank(pickupHours));
        profile.setWhatsappPhone(blank(whatsappPhone));
        profile.setJobsPhone(blank(jobsPhone));
        profile.setInstagramUrl(blank(instagramUrl));
        profile.setTiktokUrl(blank(tiktokUrl));
        profile.setMapPoint(blank(mapPoint));
        profile.setFoundedYear(foundedYear);
        profile.setExternalLinks(blank(externalLinks));
        return companyProfileRepository.save(profile);
    }

    /** Админка › Настройки › Окна выдачи: первое окно с часа, последнее до часа, заказов в окне, дни пн…вс («1111110»). */
    public CompanyProfile saveSlots(int firstHour, int lastHour, int capacity, String days) {
        if (firstHour < 0 || lastHour > 24 || lastHour <= firstHour) throw new IllegalArgumentException("«Последнее до» — позже первого окна.");
        if (capacity < 1 || capacity > 50) throw new IllegalArgumentException("Заказов в окне — от 1 до 50.");
        if (days == null || !days.matches("[01]{7}") || !days.contains("1")) throw new IllegalArgumentException("Оставьте хотя бы один день выдачи.");
        CompanyProfile profile = get();
        profile.setPickupFirstHour(firstHour);
        profile.setPickupLastHour(lastHour);
        profile.setPickupCapacity(capacity);
        profile.setPickupDays(days);
        return companyProfileRepository.save(profile);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** Настройки › Магазин: выбор дня/времени выдачи и способа оплаты на /shop. */
    public CompanyProfile saveShopOptions(boolean pickupChoice, boolean paymentChoice) {
        CompanyProfile profile = get();
        profile.setShopPickupChoice(pickupChoice);
        profile.setShopPaymentChoice(paymentChoice);
        return companyProfileRepository.save(profile);
    }
}
