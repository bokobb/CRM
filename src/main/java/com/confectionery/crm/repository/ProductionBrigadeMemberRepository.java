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

    /** Бригады неотменённых заявок за период — табель, список сотрудников, последняя смена. */
    @Query("""
            select m from ProductionBrigadeMember m join fetch m.employee join fetch m.request r
            where r.documentDate between :from and :to and r.status <> :cancelled
            """)
    List<ProductionBrigadeMember> findForPeriod(@Param("from") LocalDate from, @Param("to") LocalDate to,
                                                @Param("cancelled") ProductionRequestStatus cancelled);

    /** Бригады сотрудника — новые сверху (карточка сотрудника). */
    @Query("""
            select m from ProductionBrigadeMember m join fetch m.request r
            where m.employee.id = :employeeId and r.status <> :cancelled and r.documentDate <= :to
            order by r.documentDate desc, r.id desc
            """)
    List<ProductionBrigadeMember> findForEmployee(@Param("employeeId") Long employeeId, @Param("to") LocalDate to,
                                                  @Param("cancelled") ProductionRequestStatus cancelled);
}
