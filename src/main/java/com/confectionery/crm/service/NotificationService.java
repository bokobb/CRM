package com.confectionery.crm.service;

import com.confectionery.crm.domain.Notification;
import com.confectionery.crm.domain.Order;
import com.confectionery.crm.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Точка автоматизации уведомлений. Канал реализован через простой лог + опциональную
 * отправку по email (см. crm.notifications.email-enabled). Реальную интеграцию с
 * SMS/Telegram/почтовым провайдером можно добавить как ещё один канал, не трогая вызывающий код.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final NotificationRepository notificationRepository;
    private final JavaMailSender mailSender;

    @Value("${crm.notifications.email-enabled:false}")
    private boolean emailEnabled;

    public Notification notify(String recipient, String message, Order order) {
        Notification notification = new Notification();
        notification.setRecipient(recipient);
        notification.setMessage(message);
        notification.setOrder(order);

        boolean sentByEmail = emailEnabled && EMAIL.matcher(recipient).matches() && trySendEmail(recipient, message);
        notification.setChannel(sentByEmail ? "EMAIL" : "LOG");
        notification.setSent(true);

        log.info("[Уведомление -> {}] {}", recipient, message);
        return notificationRepository.save(notification);
    }

    private boolean trySendEmail(String recipient, String message) {
        try {
            SimpleMailMessage mail = new SimpleMailMessage();
            mail.setTo(recipient);
            mail.setSubject("Кондитерская CRM");
            mail.setText(message);
            mailSender.send(mail);
            return true;
        } catch (Exception e) {
            log.warn("Не удалось отправить email {}: {}", recipient, e.getMessage());
            return false;
        }
    }

    /** Не дублировать одно и то же предупреждение об остатках чаще, чем раз в сутки. */
    public boolean wasNotifiedRecently(String recipient, String messagePrefix) {
        return notificationRepository
                .findFirstByRecipientAndMessageStartingWithAndCreatedAtAfterOrderByCreatedAtDesc(
                        recipient, messagePrefix, LocalDateTime.now().minusHours(24))
                .isPresent();
    }

    public List<Notification> recent() {
        return notificationRepository.findTop50ByOrderByCreatedAtDesc();
    }
}
