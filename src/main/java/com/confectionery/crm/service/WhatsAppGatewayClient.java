package com.confectionery.crm.service;

import com.confectionery.crm.api.dto.WhatsAppSessionDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.util.Map;

/** Отправляет исходящие сообщения через Node.js-шлюз WhatsApp (см. whatsapp-gateway/). */
@Slf4j
@Service
public class WhatsAppGatewayClient {

    private final RestClient restClient;
    private final String webhookSecret;

    public WhatsAppGatewayClient(@Value("${crm.whatsapp.gateway-url}") String gatewayUrl,
                                  @Value("${crm.whatsapp.webhook-secret:}") String webhookSecret) {
        this.restClient = RestClient.builder().baseUrl(gatewayUrl).build();
        this.webhookSecret = webhookSecret;
    }

    /** true, если сообщение реально отправлено (шлюз ответил 2xx). */
    public boolean sendMessage(String to, String text) {
        try {
            restClient.post()
                    .uri("/send")
                    .header("X-Webhook-Secret", webhookSecret)
                    .body(Map.of("to", to, "text", text))
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.warn("Не удалось отправить сообщение в WhatsApp {}: {}", to, e.getMessage());
            return false;
        }
    }

    /** true, если документ реально отправлен (шлюз ответил 2xx). */
    public boolean sendDocument(String to, byte[] pdfBytes, String filename, String caption) {
        try {
            restClient.post()
                    .uri("/send-document")
                    .header("X-Webhook-Secret", webhookSecret)
                    .body(Map.of(
                            "to", to,
                            "filename", filename,
                            "caption", caption,
                            "dataBase64", Base64.getEncoder().encodeToString(pdfBytes)
                    ))
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.warn("Не удалось отправить документ в WhatsApp {}: {}", to, e.getMessage());
            return false;
        }
    }

    /** "unavailable", если шлюз не отвечает — не бросаем наружу, дашборд/настройки покажут это как статус. */
    public WhatsAppSessionDto getStatus() {
        try {
            return restClient.get()
                    .uri("/status")
                    .header("X-Webhook-Secret", webhookSecret)
                    .retrieve()
                    .body(WhatsAppSessionDto.class);
        } catch (Exception e) {
            log.warn("Не удалось получить статус WhatsApp-шлюза: {}", e.getMessage());
            return new WhatsAppSessionDto("unavailable", null);
        }
    }

    /** null, если QR сейчас недоступен (не в процессе подключения) или шлюз не отвечает. */
    public byte[] getQrCode() {
        try {
            return restClient.get()
                    .uri("/qr")
                    .header("X-Webhook-Secret", webhookSecret)
                    .retrieve()
                    .body(byte[].class);
        } catch (Exception e) {
            log.warn("Не удалось получить QR-код WhatsApp: {}", e.getMessage());
            return null;
        }
    }

    /** true, если шлюз принял запрос на разрыв сессии (реальный разрыв и новый QR — асинхронно на его стороне). */
    public boolean logout() {
        try {
            restClient.post()
                    .uri("/logout")
                    .header("X-Webhook-Secret", webhookSecret)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.warn("Не удалось разорвать сессию WhatsApp: {}", e.getMessage());
            return false;
        }
    }
}
