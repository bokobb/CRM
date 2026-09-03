package com.confectionery.crm.api;

import com.confectionery.crm.api.dto.WhatsAppSessionDto;
import com.confectionery.crm.service.WhatsAppGatewayClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Настройки -> WhatsApp-сессия: статус подключения шлюза, QR для нового подключения, разрыв. */
@RestController
@RequestMapping("/api/whatsapp/session")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT')")
public class WhatsAppSessionApiController {

    private final WhatsAppGatewayClient gatewayClient;

    @GetMapping("/status")
    public WhatsAppSessionDto status() {
        return gatewayClient.getStatus();
    }

    @GetMapping(value = "/qr", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> qr() {
        byte[] png = gatewayClient.getQrCode();
        if (png == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(png);
    }

    @PostMapping("/logout")
    public void logout() {
        gatewayClient.logout();
    }
}
