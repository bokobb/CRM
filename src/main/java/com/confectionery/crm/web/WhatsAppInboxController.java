package com.confectionery.crm.web;

import com.confectionery.crm.domain.WhatsAppInboundMessage;
import com.confectionery.crm.domain.WhatsAppInboundStatus;
import com.confectionery.crm.repository.WhatsAppInboundMessageRepository;
import com.confectionery.crm.service.ProductService;
import com.confectionery.crm.service.WhatsAppOrderIntakeService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Заказы из WhatsApp, которые ИИ не смог распознать полностью — администратор вручную
 * подбирает товар для непонятных позиций (см. WhatsAppOrderIntakeService.resolvePending).
 */
@Controller
@RequestMapping("/whatsapp-inbox")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SALES','ADMIN','ACCOUNTANT')")
public class WhatsAppInboxController {

    private final WhatsAppInboundMessageRepository inboundRepository;
    private final ProductService productService;
    private final WhatsAppOrderIntakeService intakeService;

    @GetMapping
    public String list(Model model) {
        model.addAttribute("activePage", "whatsapp-inbox");
        model.addAttribute("messages", inboundRepository.findByStatusOrderByCreatedAtDesc(WhatsAppInboundStatus.PENDING));
        return "whatsapp/inbox";
    }

    @GetMapping("/{id}")
    public String view(@PathVariable Long id, Model model) {
        WhatsAppInboundMessage message = inboundRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Сообщение не найдено: " + id));
        model.addAttribute("activePage", "whatsapp-inbox");
        model.addAttribute("message", message);
        model.addAttribute("allProducts", productService.findAll());
        return "whatsapp/resolve";
    }

    @PostMapping("/{id}/resolve")
    public String resolve(@PathVariable Long id, @RequestParam Map<String, String> params, Model model) {
        Map<Long, Long> resolutions = new HashMap<>();
        params.forEach((key, value) -> {
            if (key.startsWith("item_") && value != null && !value.isBlank()) {
                resolutions.put(Long.valueOf(key.substring(5)), Long.valueOf(value));
            }
        });
        try {
            intakeService.resolvePending(id, resolutions);
        } catch (IllegalArgumentException | IllegalStateException e) {
            WhatsAppInboundMessage message = inboundRepository.findById(id).orElseThrow();
            model.addAttribute("activePage", "whatsapp-inbox");
            model.addAttribute("message", message);
            model.addAttribute("allProducts", productService.findAll());
            model.addAttribute("error", e.getMessage());
            return "whatsapp/resolve";
        }
        return "redirect:/whatsapp-inbox";
    }

    @PostMapping("/{id}/ignore")
    public String ignore(@PathVariable Long id) {
        intakeService.ignore(id);
        return "redirect:/whatsapp-inbox";
    }
}
