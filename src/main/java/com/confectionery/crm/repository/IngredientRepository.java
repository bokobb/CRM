package com.confectionery.crm.repository;

import com.confectionery.crm.domain.Ingredient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface IngredientRepository extends JpaRepository<Ingredient, Long> {

    @Query("select i from Ingredient i where i.active = true and i.quantityInStock < i.minThreshold")
    List<Ingredient> findLowStock();

    List<Ingredient> findByActiveTrue();

    /** См. StockService.receiveProductForProduction — сопоставление полуфабриката с сырьём по имени. */
    Optional<Ingredient> findByNameIgnoreCase(String name);

    /** Сопоставление строки документа 1С с сырьём по GUID номенклатуры (см. OneCPurchaseImportService). */
    Optional<Ingredient> findByOneCNomenclatureKey(String oneCNomenclatureKey);
}
