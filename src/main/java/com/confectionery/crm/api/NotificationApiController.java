package com.confectionery.crm.api;

import com.confectionery.crm.api.dto.NotificationDto;
import com.confectionery.crm.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
}
