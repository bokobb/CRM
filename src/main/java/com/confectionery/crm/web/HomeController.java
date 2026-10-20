package com.confectionery.crm.web;

import com.confectionery.crm.service.PromoPageService;
import com.confectionery.crm.service.SeoService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * "/" — рекламная страница (templates/promo.html, данные — PromoPageService) для гостей и клиентов
 * магазина (логотип в магазине ведёт сюда, клиент /shop не входит через Spring Security). Сотрудник,
 * вошедший в админку, попадает в неё.
 */
@Controller
@RequiredArgsConstructor
public class HomeController {

    private final PromoPageService promoPageService;
    private final SeoService seoService;

    @GetMapping("/")
    public String home(Model model, Authentication authentication) {
        if (authentication != null && authentication.isAuthenticated() && !(authentication instanceof AnonymousAuthenticationToken)) {
            return "redirect:/admin";
        }
        PromoPageService.Page page = promoPageService.build();
        model.addAttribute("page", page);
        model.addAttribute("seo", seoService.home(SeoAdvice.baseUrl(), page));
        return "promo";
    }
}
