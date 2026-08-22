package com.confectionery.crm.repository;

import com.confectionery.crm.domain.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findTop50ByOrderByCreatedAtDesc();

    Optional<Notification> findFirstByRecipientAndMessageStartingWithAndCreatedAtAfterOrderByCreatedAtDesc(
            String recipient, String messagePrefix, LocalDateTime after);
}
