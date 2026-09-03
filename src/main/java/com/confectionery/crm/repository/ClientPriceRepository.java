package com.confectionery.crm.repository;

import com.confectionery.crm.domain.ClientPrice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ClientPriceRepository extends JpaRepository<ClientPrice, Long> {
    Optional<ClientPrice> findByClientIdAndProductId(Long clientId, Long productId);

    List<ClientPrice> findByClientId(Long clientId);

    List<ClientPrice> findByProductId(Long productId);
}
