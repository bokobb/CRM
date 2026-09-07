package com.confectionery.crm.repository;

import com.confectionery.crm.domain.Counterparty;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CounterpartyRepository extends JpaRepository<Counterparty, Long> {

    List<Counterparty> findAllByOrderByNameAsc();

    Optional<Counterparty> findByOneCContragentKey(String oneCContragentKey);
}
