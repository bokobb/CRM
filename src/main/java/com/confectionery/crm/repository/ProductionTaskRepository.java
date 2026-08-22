package com.confectionery.crm.repository;

import com.confectionery.crm.domain.ProductionTask;
import com.confectionery.crm.domain.ProductionTaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProductionTaskRepository extends JpaRepository<ProductionTask, Long> {

    List<ProductionTask> findByStatusOrderByCreatedAtAsc(ProductionTaskStatus status);

    List<ProductionTask> findByOrderId(Long orderId);

    List<ProductionTask> findAllByOrderByCreatedAtAsc();
}
