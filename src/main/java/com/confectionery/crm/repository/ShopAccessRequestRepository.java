package com.confectionery.crm.repository;

import com.confectionery.crm.domain.ShopAccessRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface ShopAccessRequestRepository extends JpaRepository<ShopAccessRequest, Long> {

    List<ShopAccessRequest> findByStatusOrderByCreatedAtDesc(String status);

    List<ShopAccessRequest> findByStatusInOrderByCreatedAtDesc(List<String> statuses);

    long countByCreatedAtAfter(LocalDateTime after);
}
