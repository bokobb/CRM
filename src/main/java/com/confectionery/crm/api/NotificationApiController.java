package com.confectionery.crm.api;

import com.confectionery.crm.api.dto.NotificationDto;
import com.confectionery.crm.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationApiController {

    private final NotificationService notificationService;

    @GetMapping
    public List<NotificationDto> list() {
        return notificationService.recent().stream().map(NotificationDto::from).toList();
    }

    /**
     * Та же группировка "один заказ — одна карточка", что и на дашборде, но без ограничения в 8 записей.
     * from/to не заданы — все даты; мобильное приложение по умолчанию передаёт сегодняшний день.
     */
    @GetMapping("/grouped")
    public NotificationService.Grouped grouped(@RequestParam(required = false) LocalDate from,
                                                @RequestParam(required = false) LocalDate to) {
        if (from == null || to == null) {
            return notificationService.grouped(50, 50);
        }
        return notificationService.grouped(50, 50, from.atStartOfDay(), to.plusDays(1).atStartOfDay());
    }
}
