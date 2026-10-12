package com.confectionery.crm.repository;

import com.confectionery.crm.domain.Vacancy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VacancyRepository extends JpaRepository<Vacancy, Long> {

    List<Vacancy> findAllByOrderBySortOrderAscIdAsc();

    List<Vacancy> findByActiveTrueOrderBySortOrderAscIdAsc();
}
