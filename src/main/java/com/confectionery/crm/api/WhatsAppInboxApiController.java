package com.confectionery.crm.api;

import com.confectionery.crm.api.dto.WhatsAppInboundMessageDto;
import com.confectionery.crm.domain.WhatsAppInboundStatus;
import com.confectionery.crm.repository.WhatsAppInboundMessageRepository;
import com.confectionery.crm.service.WhatsAppOrderIntakeService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Заказы из WhatsApp, которые ИИ не смог распознать полностью — мобильный аналог
 * web/WhatsAppInboxController, чтобы уточнять позиции прямо с телефона (см.
 * WhatsAppOrderIntakeService.resolvePending — общая логика с веб-версией).
 */
@RestController
@RequestMapping("/api/whatsapp-inbox")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SALES','ADMIN','ACCOUNTANT')")
public class WhatsAppInboxApiController {

    private final WhatsAppInboundMessageRepository inboundRepository;
    private final WhatsAppOrderIntakeService intakeService;

    @GetMapping
    public List<WhatsAppInboundMessageDto> list() {
        return inboundRepository.findByStatusOrderByCreatedAtDesc(WhatsAppInboundStatus.PENDING)
                .stream().map(WhatsAppInboundMessageDto::from).toList();
    }

    /** Тело запроса: {"&lt;idПозиции&gt;": &lt;idТовара&gt;, ...} — только для нераспознанных позиций. */
    @PostMapping("/{id}/resolve")
    public void resolve(@PathVariable Long id, @RequestBody Map<String, Long> resolutions) {
        Map<Long, Long> parsed = resolutions.entrySet().stream()
                .collect(Collectors.toMap(e -> Long.valueOf(e.getKey()), Map.Entry::getValue));
        intakeService.resolvePending(id, parsed);
    }

    @PostMapping("/{id}/ignore")
    public void ignore(@PathVariable Long id) {
        intakeService.ignore(id);
    }
}
