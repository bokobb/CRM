package com.confectionery.crm.service;

import com.confectionery.crm.api.dto.NotificationDto;
import com.confectionery.crm.api.dto.OrderNotificationGroupDto;
import com.confectionery.crm.domain.Notification;
import com.confectionery.crm.domain.Order;
import com.confectionery.crm.domain.Role;
import com.confectionery.crm.repository.NotificationRepository;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.Message;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private static final int MESSAGE_MAX_LENGTH = 2000; // должно совпадать с Notification.message length

    private final NotificationRepository notificationRepository;
    private final JavaMailSender mailSender;

    @Value("${crm.notifications.email-enabled:false}")
    private boolean emailEnabled;

    @Value("${crm.public-url:https://yug-sladosti.kz}")
    private String publicUrl;

    public Notification notify(String recipient, String message, Order order) {
        message = truncate(message);
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

    /** Защита от переполнения {@code notifications.message varchar(2000)} — например, список
     * несопоставленных документов 1С может оказаться сколь угодно длинным. */
    private String truncate(String message) {
        if (message == null || message.length() <= MESSAGE_MAX_LENGTH) {
            return message;
        }
        return message.substring(0, MESSAGE_MAX_LENGTH - 1) + "…";
    }

    private boolean trySendEmail(String recipient, String message) {
        try {
            SimpleMailMessage mail = new SimpleMailMessage();
            mail.setTo(recipient);
            mail.setSubject("Юг Сладости");
            mail.setText(message);
            mailSender.send(mail);
            return true;
        } catch (Exception e) {
            log.warn("Не удалось отправить email {}: {}", recipient, e.getMessage());
            return false;
        }
    }

    /**
     * Push-уведомление всем сотрудникам с данной ролью (тема FCM {@code role_<ROLE>}, на неё
     * подписывается мобильное приложение после логина — см. mobile/lib/services/api_client.dart).
     * Если Firebase не подключён (см. FirebaseConfig), запись в журнале всё равно создаётся,
     * просто без реальной отправки push.
     */
    public Notification notifyRole(Role role, String message, Order order) {
        message = truncate(message);
        Notification notification = new Notification();
        notification.setRecipient("role:" + role.name());
        notification.setMessage(message);
        notification.setOrder(order);
        notification.setChannel(sendPush(role, message, order) ? "PUSH" : "LOG");
        notification.setSent(true);

        log.info("[Уведомление -> роль {}] {}", role, message);
        return notificationRepository.save(notification);
    }

    /** [order] может быть null — например, пуш "нужно уточнить заказ из WhatsApp" шлётся ещё до его создания. */
    private boolean sendPush(Role role, String message, Order order) {
        try {
            Message.Builder builder = Message.builder()
                    .setTopic("role_" + role.name())
                    .setNotification(com.google.firebase.messaging.Notification.builder()
                            .setTitle("Юг Сладости")
                            .setBody(message)
                            .build())
                    // Веб-версия (iPhone, «На экран Домой»): иконка и куда вести по нажатию.
                    .setWebpushConfig(com.google.firebase.messaging.WebpushConfig.builder()
                            .setNotification(com.google.firebase.messaging.WebpushNotification.builder()
                                    .setTitle("Юг Сладости")
                                    .setBody(message)
                                    .setIcon(publicUrl + "/app/icons/Icon-192.png")
                                    .build())
                            .setFcmOptions(com.google.firebase.messaging.WebpushFcmOptions.withLink(publicUrl + "/app/index.html"))
                            .build());
            if (order != null) {
                builder.putData("orderId", String.valueOf(order.getId()));
            }
            FirebaseMessaging.getInstance().send(builder.build());
            return true;
        } catch (Exception e) {
            log.debug("Push не отправлен ({}): {}", role, e.getMessage());
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

    /**
     * Уведомления, сгруппированные по заказу (одна карточка — один заказ, полная история —
     * по нажатию, см. дашборд и экран "Уведомления"). Один и тот же текст, отправленный сразу
     * нескольким ролям (например, ACCOUNTANT и ADMIN при отгрузке), схлопывается в одну запись
     * истории — иначе каждое событие визуально дублируется.
     *
     * <p>{@code viewerRole} ограничивает список только тем, что реально адресовано этой роли —
     * без этого зав. складом ГП видел бы уведомления зав. складом сырья и наоборот. ADMIN и
     * ACCOUNTANT видят всё (та же логика полного доступа, что и в меню приложения).
     */
    public Grouped grouped(int orderLimit, int generalLimit, Role viewerRole) {
        return groupedFrom(filterForRole(recent(), viewerRole), orderLimit, generalLimit);
    }

    /** То же самое, но только за один календарный день — для фильтра по дате в приложении. */
    public Grouped grouped(int orderLimit, int generalLimit, LocalDateTime dayStart, LocalDateTime dayEnd, Role viewerRole) {
        return groupedFrom(
                filterForRole(notificationRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(dayStart, dayEnd), viewerRole),
                orderLimit, generalLimit);
    }

    /**
     * Уведомление видно роли, если оно адресовано именно ей ({@code role:<ROLE>}), либо роли
     * ADMIN/ACCOUNTANT (у них полный доступ), либо это предупреждение "склад" о низком остатке
     * СЫРЬЯ — оно относится к зоне ответственности WAREHOUSE_RAW, а не WAREHOUSE_FG.
     */
    private List<Notification> filterForRole(List<Notification> source, Role viewerRole) {
        if (viewerRole == null || viewerRole == Role.ADMIN || viewerRole == Role.ACCOUNTANT) {
            return source;
        }
        String ownRecipient = "role:" + viewerRole.name();
        return source.stream()
                .filter(n -> n.getRecipient().equals(ownRecipient)
                        || (viewerRole == Role.WAREHOUSE_RAW && n.getRecipient().equals("склад")))
                .toList();
    }

    private Grouped groupedFrom(List<Notification> source, int orderLimit, int generalLimit) {
        Map<Long, List<Notification>> byOrder = new LinkedHashMap<>();
        List<Notification> general = new ArrayList<>();
        for (Notification n : source) {
            if (n.getOrder() != null) {
                byOrder.computeIfAbsent(n.getOrder().getId(), k -> new ArrayList<>()).add(n);
            } else {
                general.add(n);
            }
        }
        List<OrderNotificationGroupDto> groups = byOrder.values().stream()
                .limit(orderLimit)
                .map(this::toGroupDto)
                .toList();
        return new Grouped(groups, general.stream().limit(generalLimit).map(NotificationDto::from).toList());
    }

    private OrderNotificationGroupDto toGroupDto(List<Notification> history) {
        Order order = history.get(0).getOrder();
        Set<String> seenMessages = new LinkedHashSet<>();
        List<NotificationDto> deduped = new ArrayList<>();
        for (Notification n : history) {
            if (seenMessages.add(n.getMessage())) {
                deduped.add(NotificationDto.from(n));
            }
        }
        return new OrderNotificationGroupDto(
                order.getId(), order.getClient().getName(),
                order.getStatus().name(), order.getStatus().getLabel(),
                history.get(0).getCreatedAt(),
                deduped
        );
    }

    public record Grouped(List<OrderNotificationGroupDto> orderGroups, List<NotificationDto> general) {
    }
}
