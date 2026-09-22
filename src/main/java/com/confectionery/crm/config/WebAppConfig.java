package com.confectionery.crm.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Раздаёт веб-сборку Flutter-приложения (см. mobile/, {@code flutter build web}) как статику
 * по /app/** — тот же интерфейс, что и в мобильном приложении, но открывается в браузере
 * (задумано под "Добавить на экран Домой" в Safari на iPhone, см. переписку 2026-09-22).
 *
 * <p>Папка ВНЕШНЯЯ (crm.web-app.dir), не в classpath — как и {@link UploadConfig}: сборку
 * (десятки МБ, meняется независимо от бэкенда) не коммитим в git и не пересобираем backend
 * ради неё, просто копируем build/web поверх этой папки на сервере (rsync/scp), аналогично
 * тому, как раздаётся APK (см. память yandex_cloud_deployment — /etc/nginx/snippets/downloads.conf).
 *
 * <p>index.html без кеша (может обновиться), сами хешированные ассеты (main.dart.js,
 * canvaskit и т.п. — Flutter сам версионирует их в имени/содержимом) — с длинным кешем,
 * чтобы повторные заходы не перекачивали по 20+ МБ каждый раз.
 */
@Configuration
public class WebAppConfig implements WebMvcConfigurer {

    @Value("${crm.web-app.dir}")
    private String webAppDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = "file:" + Path.of(webAppDir).toAbsolutePath().normalize() + "/";
        // index.html — без кеша (может обновиться при новом деплое), остальное (main.dart.js,
        // canvaskit и т.п.) — с длинным кешем: повторные заходы не перекачивают по 20+ МБ.
        registry.addResourceHandler("/app/index.html")
                .addResourceLocations(location)
                .setCacheControl(CacheControl.noCache());
        registry.addResourceHandler("/app/**")
                .addResourceLocations(location)
                .setCacheControl(CacheControl.maxAge(7, TimeUnit.DAYS).cachePublic());
    }

    /** /app и /app/ (без имени файла) — на index.html, ResourceHandler сам directory index не угадывает. */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/app", "/app/index.html");
        registry.addRedirectViewController("/app/", "/app/index.html");
    }
}
