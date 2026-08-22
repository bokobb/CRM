package com.confectionery.crm.repository;

import com.confectionery.crm.domain.Product;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, Long> {
}
