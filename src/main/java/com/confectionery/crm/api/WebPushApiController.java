package com.confectionery.crm.api;

import com.confectionery.crm.service.UserService;
import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.TopicManagementResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Push для веб-версии (iPhone, «На экран Домой»). В браузере Firebase не умеет подписываться на
 * темы сам (subscribeToTopic есть только в Android/iOS SDK), поэтому приложение присылает сюда
 * свой токен, а сервер подписывает его на тему роли — дальше уведомления идут тем же путём, что
 * и на Android (NotificationService.sendPush, тема role_<ROLE>).
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class WebPushApiController {

    private final UserService userService;

    @Value("${crm.web-app.dir}")
    private String webAppDir;

    public record TokenRequest(String token) {
    }

    @PostMapping("/api/push/web-token")
    public ResponseEntity<Map<String, Object>> register(@RequestBody TokenRequest request, Authentication authentication) {
        if (request.token() == null || request.token().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Пустой токен"));
        }
        if (FirebaseApp.getApps().isEmpty()) {
            return ResponseEntity.status(503).body(Map.of("error", "Push на сервере не настроен"));
        }
        String topic = "role_" + userService.roleOf(authentication.getName()).name();
        try {
            TopicManagementResponse r = FirebaseMessaging.getInstance().subscribeToTopic(List.of(request.token()), topic);
            if (r.getFailureCount() > 0) {
                String reason = r.getErrors().get(0).getReason();
                log.warn("Web push: токен не подписан на {}: {}", topic, reason);
                return ResponseEntity.status(502).body(Map.of("error", "Firebase отклонил токен: " + reason));
            }
            log.info("Web push: токен подписан на {} ({})", topic, authentication.getName());
            return ResponseEntity.ok(Map.of("topic", topic));
        } catch (Exception e) {
            log.warn("Web push: не удалось подписать токен на {}: {}", topic, e.getMessage());
            return ResponseEntity.status(502).body(Map.of("error", "Не удалось подписать: " + e.getMessage()));
        }
    }

    /**
     * Фоновый обработчик Firebase для браузера. Firebase JS SDK ищет его строго в корне сайта
     * (/firebase-messaging-sw.js), а веб-сборка лежит под /app/ — отдаём тот же файл из папки
     * сборки (crm.web-app.dir) отдельным адресом. Без кеша — чтобы правки доходили сразу.
     */
    @GetMapping("/firebase-messaging-sw.js")
    public ResponseEntity<byte[]> serviceWorker() throws Exception {
        Path file = Path.of(webAppDir).resolve("firebase-messaging-sw.js").toAbsolutePath().normalize();
        if (!Files.exists(file)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("application/javascript"))
                .cacheControl(CacheControl.noCache())
                .body(Files.readAllBytes(file));
    }
}
