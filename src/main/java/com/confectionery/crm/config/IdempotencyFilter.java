package com.confectionery.crm.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Защита от задвоения при плохом интернете или долгом ответе 1С: мобильное приложение шлёт в
 * каждом изменяющем запросе заголовок {@code Idempotency-Key} и повторяет тот же ключ, если
 * такой же запрос ещё в пути или прошлая попытка закончилась без ответа (обрыв, тайм-аут, 504
 * от nginx — см. IdempotentClient в mobile). Здесь запрос с уже виденным ключом не выполняется
 * второй раз: если первый ещё идёт — ждём его и отдаём тот же ответ; если уже выполнился
 * успешно — отдаём сохранённый ответ. Неуспешный (не 2xx) ответ не запоминается — повтор
 * выполнится заново.
 *
 * <p>Хранится в памяти (один экземпляр backend), 30 минут. Запросы без заголовка (старые
 * версии приложения, веб) проходят как раньше.
 *
 * <p>Обычный сервлет-фильтр без явного порядка — стоит после Spring Security, поэтому
 * пользователь уже известен, и ключ одного пользователя не пересекается с чужим.
 */
@Slf4j
@Component
public class IdempotencyFilter extends OncePerRequestFilter {

    static final String HEADER = "Idempotency-Key";
    private static final long TTL_MILLIS = TimeUnit.MINUTES.toMillis(30);
    /** Дольше, чем nginx ждёт backend (proxy_read_timeout 300s). */
    private static final long WAIT_SECONDS = 330;

    private record Cached(int status, String contentType, byte[] body) {
    }

    private static final class Entry {
        final CompletableFuture<Cached> result = new CompletableFuture<>();
        final long createdAt = System.currentTimeMillis();
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String method = request.getMethod();
        boolean mutating = "POST".equals(method) || "PUT".equals(method) || "DELETE".equals(method);
        String key = request.getHeader(HEADER);
        return !mutating || key == null || key.isBlank() || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        purgeExpired();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String user = auth != null ? auth.getName() : "-";
        String key = user + "|" + request.getMethod() + "|" + request.getRequestURI() + "|" + request.getHeader(HEADER);

        while (true) {
            Entry fresh = new Entry();
            Entry existing = entries.putIfAbsent(key, fresh);
            if (existing == null) {
                execute(key, fresh, request, response, chain);
                return;
            }
            Cached cached;
            try {
                cached = existing.result.get(WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                writeJson(response, HttpServletResponse.SC_CONFLICT,
                        "Такой же запрос ещё выполняется — подождите и обновите экран");
                return;
            } catch (Exception e) {
                cached = null;
            }
            if (cached != null) {
                log.info("Повтор запроса {} {} от {} — отдан сохранённый ответ, повторно не выполняется",
                        request.getMethod(), request.getRequestURI(), user);
                replay(cached, response);
                return;
            }
            // Первая попытка не удалась (запись уже убрана) — выполняем заново.
        }
    }

    private void execute(String key, Entry entry, HttpServletRequest request, HttpServletResponse response,
                         FilterChain chain) throws ServletException, IOException {
        ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
        Cached cached = null;
        try {
            chain.doFilter(request, wrapper);
            int status = wrapper.getStatus();
            if (status >= 200 && status < 300) {
                cached = new Cached(status, wrapper.getContentType(), wrapper.getContentAsByteArray());
            }
        } finally {
            if (cached == null) {
                entries.remove(key, entry);
            }
            entry.result.complete(cached);
            wrapper.copyBodyToResponse();
        }
    }

    private static void replay(Cached cached, HttpServletResponse response) throws IOException {
        response.setStatus(cached.status());
        if (cached.contentType() != null) {
            response.setContentType(cached.contentType());
        }
        response.setHeader("Idempotent-Replay", "true");
        response.setContentLength(cached.body().length);
        response.getOutputStream().write(cached.body());
    }

    private static void writeJson(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getOutputStream().write(("{\"message\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8));
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        entries.entrySet().removeIf(e -> e.getValue().result.isDone() && now - e.getValue().createdAt > TTL_MILLIS);
    }
}
