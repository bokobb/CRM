package com.confectionery.crm.repository;

import com.confectionery.crm.domain.Ingredient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface IngredientRepository extends JpaRepository<Ingredient, Long> {

    @Query("select i from Ingredient i where i.quantityInStock < i.minThreshold")
    List<Ingredient> findLowStock();
}
