package com.confectionery.crm.web;

import com.confectionery.crm.service.SeoService;
import com.confectionery.crm.service.VacancyService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.List;

/**
 * "seo" в модели страниц сайта (контакты, вакансии, магазин): заголовок, описание, разметка schema.org
 * и коды подтверждения. Главной "seo" кладёт HomeController — ей нужен каталог, который он и так строит.
 */
@ControllerAdvice(assignableTypes = {HomeController.class, SitePagesController.class, ShopController.class})
@RequiredArgsConstructor
public class SeoAdvice {

    private final SeoService seoService;
    private final VacancyService vacancyService;

    @ModelAttribute("seo")
    public SeoService.Seo seo(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        SeoService.Kind kind = switch (path) {
            case "/" -> null;
            case "/contacts" -> SeoService.Kind.CONTACTS;
            case "/vacancies" -> SeoService.Kind.VACANCIES;
            case "/shop", "/shop/" -> SeoService.Kind.SHOP;
            default -> SeoService.Kind.PRIVATE;
        };
        if (kind == null) return null;
        return seoService.page(kind, baseUrl(),
                kind == SeoService.Kind.VACANCIES ? vacancyService.findActive() : List.of());
    }

    static String baseUrl() {
        return ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
    }
}
