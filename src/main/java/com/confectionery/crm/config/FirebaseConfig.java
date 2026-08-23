package com.confectionery.crm.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.InputStream;

/**
 * Инициализирует Firebase Admin SDK для отправки push-уведомлений по ролям (см.
 * NotificationService.notifyRole). Не обязателен для работы приложения: пока пользователь
 * не положил service-account JSON (см. crm.firebase.credentials-path), push просто не
 * отправляется — остальной функционал (статусы заказов, журнал уведомлений) не затрагивается.
 */
@Slf4j
@Component
public class FirebaseConfig {

    private final ResourcePatternResolver resourceResolver;
    private final String credentialsPath;

    public FirebaseConfig(ResourcePatternResolver resourceResolver,
                           @Value("${crm.firebase.credentials-path:classpath:firebase-service-account.json}") String credentialsPath) {
        this.resourceResolver = resourceResolver;
        this.credentialsPath = credentialsPath;
    }

    @PostConstruct
    public void init() {
        Resource resource = resourceResolver.getResource(credentialsPath);
        if (!resource.exists()) {
            log.warn("Firebase credentials не найдены ({}) — push-уведомления отключены, "
                    + "остальной функционал работает как обычно", credentialsPath);
            return;
        }
        try (InputStream in = resource.getInputStream()) {
            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(in))
                    .build();
            if (FirebaseApp.getApps().isEmpty()) {
                FirebaseApp.initializeApp(options);
            }
            log.info("Firebase Admin SDK инициализирован, push-уведомления включены");
        } catch (Exception e) {
            log.warn("Не удалось инициализировать Firebase ({}) — push-уведомления отключены", e.getMessage());
        }
    }
}
