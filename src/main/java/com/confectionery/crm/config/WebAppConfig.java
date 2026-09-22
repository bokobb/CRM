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
 * <p>Один обработчик на весь /app/** с умеренным кешем — отдельная попытка завести index.html
 * своим отдельным ResourceHandler (без {@code **} в паттерне, под no-cache) сломалась
 * (2026-09-22): Spring резолвит "остаток пути" внутри точного, без-wildcard паттерна как
 * пустую строку, а не "index.html", запрос падал мимо и в итоге улетал в security-чейн для
 * остального сайта (редирект на /login) — то же самое случалось для ЛЮБОГО отсутствующего
 * файла под /app/**, просто совпало по симптому. Полдня на кеш index.html не стоят той цены.
 */
@Configuration
public class WebAppConfig implements WebMvcConfigurer {

    @Value("${crm.web-app.dir}")
    private String webAppDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = "file:" + Path.of(webAppDir).toAbsolutePath().normalize() + "/";
        registry.addResourceHandler("/app/**")
                .addResourceLocations(location)
                .setCacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePublic());
    }

    /** /app и /app/ (без имени файла) — на index.html, ResourceHandler сам directory index не угадывает. */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/app", "/app/index.html");
        registry.addRedirectViewController("/app/", "/app/index.html");
    }
}
