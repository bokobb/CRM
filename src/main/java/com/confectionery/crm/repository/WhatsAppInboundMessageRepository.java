package com.confectionery.crm.repository;

import com.confectionery.crm.domain.WhatsAppInboundMessage;
import com.confectionery.crm.domain.WhatsAppInboundStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WhatsAppInboundMessageRepository extends JpaRepository<WhatsAppInboundMessage, Long> {
    List<WhatsAppInboundMessage> findByStatusOrderByCreatedAtDesc(WhatsAppInboundStatus status);
}
