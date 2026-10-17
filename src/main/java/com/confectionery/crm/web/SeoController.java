package com.confectionery.crm.web;

import com.confectionery.crm.service.SeoService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/**
 * robots.txt и sitemap.xml для поисковиков (17.10). Адрес сайта — из запроса, как canonical на
 * страницах сайта, поэтому при переезде на другой домен ничего менять не нужно.
 */
@RestController
public class SeoController {

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
                Disallow: /h2-console

                Sitemap: %s/sitemap.xml
                """.formatted(base());
    }

    @GetMapping("/sitemap.xml")
    public ResponseEntity<String> sitemap() {
        String base = base();
        String urls = SeoService.PAGES.entrySet().stream()
                .sorted((a, b) -> a.getKey().length() - b.getKey().length())
                .map(e -> "  <url><loc>" + base + e.getKey() + "</loc><changefreq>" + e.getValue()
                        + "</changefreq><priority>" + ("/".equals(e.getKey()) ? "1.0" : "0.7") + "</priority></url>")
                .collect(Collectors.joining("\n"));
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                %s
                </urlset>
                """.formatted(urls);
        return ResponseEntity.ok().contentType(new MediaType("application", "xml", StandardCharsets.UTF_8)).body(xml);
    }

    private static String base() {
        return ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
    }
}
