package com.confectionery.crm.repository;

import com.confectionery.crm.domain.Recipe;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RecipeRepository extends JpaRepository<Recipe, Long> {

    Optional<Recipe> findByProductId(Long productId);
}
