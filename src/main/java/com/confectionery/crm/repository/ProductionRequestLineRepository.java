package com.confectionery.crm.repository;

import com.confectionery.crm.domain.ProductionRequestLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface ProductionRequestLineRepository extends JpaRepository<ProductionRequestLine, Long> {

    /** Для отчёта по производству за период — см. ProductionRequestService.report. */
    List<ProductionRequestLine> findByReceivedAtBetweenOrderByReceivedAtAsc(LocalDateTime from, LocalDateTime to);

    /** Для отчёта по производству за всё время (без ограничения по дате) — см. ProductionRequestService.report. */
    List<ProductionRequestLine> findByReceivedAtIsNotNullOrderByReceivedAtAsc();
}
