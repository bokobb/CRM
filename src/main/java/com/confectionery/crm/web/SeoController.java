package com.confectionery.crm.web;

import com.confectionery.crm.domain.CompanyProfile;
import com.confectionery.crm.service.PromoPageService;
import com.confectionery.crm.service.SeoService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;

/**
 * robots.txt, sitemap.xml (с фото товаров — для Google Картинок), llms.txt (справка о компании для
 * ИИ-ассистентов) и ключ IndexNow (Яндекс, Bing). Адрес сайта — из запроса, как canonical на
 * страницах сайта, поэтому при переезде на другой домен ничего менять не нужно.
 */
@RestController
@RequiredArgsConstructor
public class SeoController {

    private static final MediaType TEXT = new MediaType("text", "plain", StandardCharsets.UTF_8);

    private final PromoPageService promoPageService;
    private final SeoService seoService;

    /** Ключ IndexNow (backend.env INDEXNOW_KEY): отдаётся по /{ключ}.txt — так Яндекс и Bing проверяют сайт. */
    @Value("${crm.seo.indexnow-key:}")
    private String indexNowKey;

    @GetMapping(value = "/robots.txt", produces = "text/plain;charset=UTF-8")
    public String robots() {
        return """
                User-agent: *
                Disallow: /admin
                Disallow: /app/
                Disallow: /api/
                Disallow: /login
                Disallow: /shop/
                Disallow: /downloads/
                Disallow: /purchase-images/
                Disallow: /h2-console

                Sitemap: %s/sitemap.xml
                """.formatted(SeoAdvice.baseUrl());
    }

    @GetMapping("/sitemap.xml")
    public ResponseEntity<String> sitemap() {
        String base = SeoAdvice.baseUrl();
        List<String> images = new ArrayList<>();
        for (PromoPageService.Card card : promoPageService.build().cards()) {
            card.photos().forEach(p -> images.add(SeoService.absolute(base, p.src())));
        }
        String homeImages = new LinkedHashSet<>(images).stream().limit(1000)
                .map(u -> "<image:image><image:loc>" + xml(u) + "</image:loc></image:image>")
                .collect(Collectors.joining());
        String urls = SeoService.PAGES.entrySet().stream()
                .sorted((a, b) -> a.getKey().length() - b.getKey().length())
                .map(e -> "  <url><loc>" + base + e.getKey() + "</loc><changefreq>" + e.getValue()
                        + "</changefreq><priority>" + ("/".equals(e.getKey()) ? "1.0" : "0.7") + "</priority>"
                        + ("/".equals(e.getKey()) ? homeImages : "") + "</url>")
                .collect(Collectors.joining("\n"));
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9" xmlns:image="http://www.google.com/schemas/sitemap-image/1.1">
                %s
                </urlset>
                """.formatted(urls);
        return ResponseEntity.ok().contentType(new MediaType("application", "xml", StandardCharsets.UTF_8)).body(xml);
    }

    /** llms.txt (llmstxt.org): кто мы, где, как заказать и весь каталог — коротко и без вёрстки. */
    @GetMapping("/llms.txt")
    public ResponseEntity<String> llms() {
        String base = SeoAdvice.baseUrl();
        PromoPageService.Page page = promoPageService.build();
        CompanyProfile c = page.company();
        String name = SeoService.name(c);
        StringBuilder s = new StringBuilder();
        s.append("# ").append(name).append("\n\n");
        s.append("> ").append(seoService.companyDescription(c, page)).append("\n\n");

        s.append("## О компании\n\n");
        line(s, "Название", name);
        line(s, "Юридическое лицо", c.legalName());
        line(s, "БИН/ИИН", c.taxId());
        line(s, "На рынке с", c.getFoundedYear() == null ? null : c.getFoundedYear() + " года");
        line(s, "Адрес производства", c.getAddress());
        line(s, "Время работы", c.getWorkHours());
        line(s, "Телефон / сотрудничество", String.join(", ", SeoService.phones(c)));
        line(s, "WhatsApp", c.getWhatsappPhone());
        line(s, "Почта", c.siteEmail());
        line(s, "Instagram", c.getInstagramUrl());
        line(s, "TikTok", c.getTiktokUrl());
        line(s, "Сайт", base + "/");
        for (String link : c.externalLinkList()) line(s, "Карточка компании", link);

        s.append("\n## Как заказать\n\n");
        s.append("- Онлайн-заказ: ").append(base).append("/shop — вход по номеру телефона, у каждого клиента свои цены, ")
                .append("корзина и история заказов.\n");
        if (!SeoService.phones(c).isEmpty()) {
            s.append("- По телефону: ").append(String.join(", ", SeoService.phones(c))).append(".\n");
        }
        s.append("- Продаём оптом: магазинам, кафе, столовым, оптовым компаниям и дистрибьюторам.\n");

        s.append("\n## Каталог (").append(page.positions(page.productCount())).append(")\n");
        for (PromoPageService.Group g : page.groups()) {
            s.append("\n### ").append(g.name()).append("\n\n");
            for (PromoPageService.Card card : page.cards()) {
                if (!g.name().equals(card.group())) continue;
                s.append("- ").append(card.name());
                if (card.meta() != null && !card.meta().isBlank()) s.append(" — ").append(card.meta());
                s.append('\n');
            }
        }

        s.append("\n## Страницы\n\n");
        s.append("- [Главная и каталог](").append(base).append("/)\n");
        s.append("- [Онлайн-заказ](").append(base).append("/shop)\n");
        s.append("- [Контакты и как добраться](").append(base).append("/contacts)\n");
        s.append("- [Вакансии](").append(base).append("/vacancies)\n");
        s.append("- [Каталог в PDF](").append(base).append("/promo/katalog.pdf)\n");
        return ResponseEntity.ok().contentType(TEXT).body(s.toString());
    }

    @GetMapping("/{key:[A-Za-z0-9-]{8,128}}.txt")
    public ResponseEntity<String> indexNowKey(@PathVariable String key) {
        if (indexNowKey.isBlank() || !indexNowKey.equals(key)) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().contentType(TEXT).body(indexNowKey);
    }

    private static void line(StringBuilder s, String label, String value) {
        if (value != null && !value.isBlank()) s.append("- ").append(label).append(": ").append(value.strip()).append('\n');
    }

    private static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
