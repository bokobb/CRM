package com.confectionery.crm.repository;

import com.confectionery.crm.domain.Order;
import com.confectionery.crm.domain.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrderRepository extends JpaRepository<Order, Long> {

    List<Order> findByStatusOrderByCreatedAtDesc(OrderStatus status);

    List<Order> findAllByOrderByCreatedAtDesc();

    long countByStatus(OrderStatus status);
}
