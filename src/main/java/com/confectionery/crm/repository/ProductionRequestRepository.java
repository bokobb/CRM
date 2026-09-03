package com.confectionery.crm.repository;

import com.confectionery.crm.domain.ProductionRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProductionRequestRepository extends JpaRepository<ProductionRequest, Long> {

    List<ProductionRequest> findAllByOrderByCreatedAtDesc();

    boolean existsByProductId(Long productId);
}
