package com.confectionery.crm.repository;

import com.confectionery.crm.domain.Product;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProductRepository extends JpaRepository<Product, Long> {

    /** Только активные (не архивные) товары — для каталога/выбора при создании новых заказов. */
    List<Product> findByActiveTrue();
}
