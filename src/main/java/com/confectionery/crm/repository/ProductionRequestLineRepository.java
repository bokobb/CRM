package com.confectionery.crm.repository;

import com.confectionery.crm.domain.ProductionRequestLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface ProductionRequestLineRepository extends JpaRepository<ProductionRequestLine, Long> {

    /** Для дневного отчёта по оприходованию — см. ProductionRequestService.dailyReport. */
    List<ProductionRequestLine> findByReceivedAtBetweenOrderByReceivedAtAsc(LocalDateTime from, LocalDateTime to);
}
