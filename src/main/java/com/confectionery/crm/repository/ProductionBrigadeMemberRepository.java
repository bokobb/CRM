package com.confectionery.crm.repository;

import com.confectionery.crm.domain.ProductionBrigadeMember;
import com.confectionery.crm.domain.ProductionRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface ProductionBrigadeMemberRepository extends JpaRepository<ProductionBrigadeMember, Long> {

    List<ProductionBrigadeMember> findByRequestId(Long requestId);

    @Query("""
            select m from ProductionBrigadeMember m join fetch m.employee join fetch m.request r
            where r.id in :requestIds
            """)
    List<ProductionBrigadeMember> findByRequestIds(@Param("requestIds") Collection<Long> requestIds);

    /** Бригады всех неотменённых заявок дня — чтобы не поставить сотрудника в две бригады. */
    @Query("""
            select m from ProductionBrigadeMember m join fetch m.employee join fetch m.request r
            where r.documentDate = :date and r.status <> :cancelled
            """)
    List<ProductionBrigadeMember> findForDay(@Param("date") LocalDate date,
                                             @Param("cancelled") ProductionRequestStatus cancelled);
}
