package com.confectionery.crm.repository;

import com.confectionery.crm.domain.Client;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ClientRepository extends JpaRepository<Client, Long> {

    /** Сопоставление документа 1С с клиентом по GUID контрагента (см. OneCOrderImportService). */
    Optional<Client> findByOneCContragentKey(String oneCContragentKey);
}
