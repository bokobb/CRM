package com.confectionery.crm.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Раздаёт загруженные фото товаров (см. FileStorageService) как статику по /product-images/**
 * с обычным HTTP-кешированием (Cache-Control: public, max-age=1 день + Last-Modified) — так
 * мобильное приложение (CachedNetworkImage) не перекачивает фото при каждом запуске, если оно
 * не менялось на сервере. См. также SecurityConfig — заголовки Cache-Control по умолчанию,
 * которые Spring Security ставит на все ответы ("no-store"), для этого пути отключены отдельно.
 */
@Configuration
public class UploadConfig implements WebMvcConfigurer {

    @Value("${crm.uploads.dir}")
    private String uploadsDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = "file:" + Path.of(uploadsDir).toAbsolutePath().normalize() + "/";
        registry.addResourceHandler("/product-images/**")
                .addResourceLocations(location)
                .setCacheControl(CacheControl.maxAge(1, TimeUnit.DAYS).cachePublic());
    }
}
