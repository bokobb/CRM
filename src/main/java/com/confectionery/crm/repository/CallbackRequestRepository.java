package com.confectionery.crm.repository;

import com.confectionery.crm.domain.CallbackRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CallbackRequestRepository extends JpaRepository<CallbackRequest, Long> {

    List<CallbackRequest> findAllByOrderByCreatedAtDesc();
}
