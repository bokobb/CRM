package com.confectionery.crm.admin;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * Значки админки — SVG 24×24 из макета (admin/icons.json: имя → содержимое svg), в шаблонах
 * {@code th:utext="${@ic.svg('orders')}"}; в скрипты весь набор отдаётся JSON-ом (layout.html).
 */
@Component("ic")
public class AdminIcons {

    private final Map<String, String> icons;
    private final String json;

    public AdminIcons(ObjectMapper mapper) throws IOException {
        try (InputStream in = new ClassPathResource("admin/icons.json").getInputStream()) {
            byte[] bytes = in.readAllBytes();
            this.icons = mapper.readValue(bytes, new TypeReference<Map<String, String>>() {
            });
            this.json = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /** Все значки заданного размера: имя → svg. В модели страниц — i (20 px), i16, i15 (см. AdminModelAdvice). */
    public Map<String, String> all(int size) {
        Map<String, String> out = new java.util.HashMap<>();
        for (String name : icons.keySet()) out.put(name, svg(name, size));
        return out;
    }

    public String svg(String name) {
        return svg(name, 20);
    }

    public String svg(String name, int size) {
        return "<svg class=\"i\" viewBox=\"0 0 24 24\" width=\"" + size + "\" height=\"" + size
                + "\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.9\" stroke-linecap=\"round\" "
                + "stroke-linejoin=\"round\" aria-hidden=\"true\">" + icons.getOrDefault(name, "") + "</svg>";
    }

    /** Значок статуса заказа по плашке (sent, new, work, ready, done, noshow, off). */
    public String status(String badge) {
        return svg("s-" + badge, 16);
    }

    public String json() {
        return json;
    }
}
