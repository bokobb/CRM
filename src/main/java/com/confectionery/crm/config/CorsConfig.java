package com.confectionery.crm.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Разрешаем CORS на /api/** для мобильного приложения (Flutter c эмулятора/устройства).
 * Это внутренний инструмент кондитерской без публичного доступа, поэтому открытый CORS
 * на API-путях — осознанный компромисс, а не то, что стоит выносить в интернет как есть.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }
}
