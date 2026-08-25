package com.confectionery.crm.api;

import com.confectionery.crm.api.dto.WhatsAppIncomingRequest;
import com.confectionery.crm.service.WhatsAppOrderIntakeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Принимает входящие сообщения от Node.js-шлюза WhatsApp (см. whatsapp-gateway/index.js).
 * Не защищён JWT (шлюз — не пользователь CRM, см. SecurityConfig), вместо этого сверяет
 * общий секрет из заголовка X-Webhook-Secret.
 */
@Slf4j
@RestController
@RequestMapping("/api/whatsapp")
@RequiredArgsConstructor
public class WhatsAppWebhookController {

    private final WhatsAppOrderIntakeService intakeService;

    @Value("${crm.whatsapp.webhook-secret:}")
    private String webhookSecret;

    @PostMapping("/incoming")
    public ResponseEntity<Void> incoming(@RequestHeader(value = "X-Webhook-Secret", required = false) String secret,
                                          @Valid @RequestBody WhatsAppIncomingRequest req) {
        if (webhookSecret.isBlank() || !webhookSecret.equals(secret)) {
            return ResponseEntity.status(403).build();
        }
        try {
            intakeService.handleIncomingMessage(req.from(), req.text(), req.name());
        } catch (Exception e) {
            // Не даём исключению уйти шлюзу как 5xx — иначе он будет ретраить одно и то же
            // сообщение; ошибка уже залогирована внутри сервиса.
            log.error("Ошибка обработки сообщения WhatsApp от {}", req.from(), e);
        }
        return ResponseEntity.accepted().build();
    }
}
