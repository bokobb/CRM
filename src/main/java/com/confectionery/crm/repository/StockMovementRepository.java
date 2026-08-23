package com.confectionery.crm.repository;

import com.confectionery.crm.domain.StockMovement;
import com.confectionery.crm.domain.StockMovementType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StockMovementRepository extends JpaRepository<StockMovement, Long> {

    List<StockMovement> findAllByOrderByCreatedAtDesc();

    List<StockMovement> findByTypeOrderByCreatedAtDesc(StockMovementType type);
}
