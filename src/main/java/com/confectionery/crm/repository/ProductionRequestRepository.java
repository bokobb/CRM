package com.confectionery.crm.repository;

import com.confectionery.crm.domain.ProductionRequest;
import com.confectionery.crm.domain.ProductionRequestStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ProductionRequestRepository extends JpaRepository<ProductionRequest, Long> {

    List<ProductionRequest> findAllByOrderByCreatedAtDesc();

    boolean existsByLinesProductId(Long productId);

    /** Заявки смены за период по дате документа — себестоимость на экране «Производство». */
    List<ProductionRequest> findByDocumentDateBetweenAndStatusNot(LocalDate from, LocalDate to, ProductionRequestStatus status);

    /**
     * SELECT ... FOR UPDATE — для всех изменений заявки. Без блокировки параллельные запросы
     * (зав. ГП оприходует, пока зав. сырья правит состав) затирали друг другу поля заявки:
     * Hibernate пишет строку целиком, и статус RECEIVED откатывался обратно в COLLECTED.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    /** Заявки с ошибкой синхронизации с 1С за последние дни — для автоповтора, когда 1С была недоступна. */
    @Query("select r from ProductionRequest r where r.oneCSyncError is not null and r.documentDate >= :since")
    List<ProductionRequest> findWithSyncErrorSince(@Param("since") LocalDate since);

    @Query("select r from ProductionRequest r where r.id = :id")
    Optional<ProductionRequest> findByIdForUpdate(@Param("id") Long id);
}
