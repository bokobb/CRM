package com.confectionery.crm.repository;

import com.confectionery.crm.domain.Purchase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface PurchaseRepository extends JpaRepository<Purchase, Long> {

    List<Purchase> findAllByOrderByCreatedAtDesc();

    List<Purchase> findByPurchaseDateBetweenOrderByCreatedAtDesc(LocalDate from, LocalDate to);

    boolean existsByCounterpartyId(Long counterpartyId);
}
