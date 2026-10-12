package com.confectionery.crm.repository;

import com.confectionery.crm.domain.VacancyReply;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface VacancyReplyRepository extends JpaRepository<VacancyReply, Long> {

    List<VacancyReply> findAllByOrderByCreatedAtDesc();

    boolean existsByVacancyId(Long vacancyId);

    long deleteByCreatedAtBefore(LocalDateTime before);
}
