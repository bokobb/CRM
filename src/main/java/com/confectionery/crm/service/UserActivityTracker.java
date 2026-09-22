package com.confectionery.crm.service;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Момент последнего запроса от реального (авторизованного) пользователя — веб-сессия или
 * мобильный /api/** с JWT. Пишется из {@link com.confectionery.crm.web.UserActivityInterceptor}
 * на каждый такой запрос. Используется 1С-опросами по расписанию (см. OneCStockSyncService,
 * OneCOrderImportService, OneCPurchaseImportService) — они пропускают прогон, если давно никто
 * не заходил: нет смысла гонять медленные запросы к 1С каждые 5 минут круглые сутки ради данных,
 * которые в этот момент никто не смотрит (2026-09-22, жалоба на "сервер стал медленно работать").
 *
 * <p>Старт (перезапуск бэкенда) считается "активным" — {@code lastActivityAt} инициализируется
 * текущим моментом, а не null/эпохой, чтобы сразу после деплоя опросы не глохли до первого
 * реального запроса.
 */
@Component
public class UserActivityTracker {

    private volatile Instant lastActivityAt = Instant.now();

    public void touch() {
        lastActivityAt = Instant.now();
    }

    public boolean recentlyActive(Duration window) {
        return Duration.between(lastActivityAt, Instant.now()).compareTo(window) <= 0;
    }
}
