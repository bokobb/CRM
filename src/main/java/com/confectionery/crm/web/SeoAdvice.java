package com.confectionery.crm.web;

import com.confectionery.crm.service.SeoService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** "seo" в модели страниц сайта (главная, контакты, вакансии): разметка schema.org и коды подтверждения. */
@ControllerAdvice(assignableTypes = {HomeController.class, SitePagesController.class})
@RequiredArgsConstructor
public class SeoAdvice {

    private final SeoService seoService;

    @ModelAttribute("seo")
    public SeoService.Seo seo() {
        return seoService.build(ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString());
    }
}
