package com.confectionery.crm.repository;

import com.confectionery.crm.domain.StockDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface StockDocumentRepository extends JpaRepository<StockDocument, Long> {

    @Query("select e.id from StockDocument e where e.oneCPending = true order by e.id")
    List<Long> findOneCPendingIds();

    @Modifying
    @Query("update StockDocument e set e.oneCPending = false where e.id = :id")
    int clearOneCPending(@Param("id") Long id);

    @Modifying
    @Query("update StockDocument e set e.oneCPending = true where e.id = :id")
    int markOneCPending(@Param("id") Long id);
}
