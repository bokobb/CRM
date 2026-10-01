package com.confectionery.crm.repository;

import com.confectionery.crm.domain.TimesheetMark;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface TimesheetMarkRepository extends JpaRepository<TimesheetMark, Long> {

    @Query("select m from TimesheetMark m join fetch m.employee where m.date between :from and :to")
    List<TimesheetMark> findForPeriod(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
