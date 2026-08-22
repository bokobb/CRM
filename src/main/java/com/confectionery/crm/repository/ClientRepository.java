package com.confectionery.crm.repository;

import com.confectionery.crm.domain.Client;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClientRepository extends JpaRepository<Client, Long> {
}
