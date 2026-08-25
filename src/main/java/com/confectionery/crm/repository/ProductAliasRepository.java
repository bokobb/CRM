package com.confectionery.crm.repository;

import com.confectionery.crm.domain.ProductAlias;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProductAliasRepository extends JpaRepository<ProductAlias, Long> {
    List<ProductAlias> findByProductId(Long productId);
}
