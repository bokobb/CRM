package com.confectionery.crm.service;

import com.confectionery.crm.domain.Ingredient;
import com.confectionery.crm.domain.Purchase;
import com.confectionery.crm.domain.StockMovement;
import com.confectionery.crm.repository.PurchaseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Закупка сырья у поставщика — обёртка над StockService.receive с дополнительными
 * закупочными подробностями (поставщик, сумма, дата), которых нет в обычном приходе
 * (см. WarehouseMovementController).
 */
@Service
@RequiredArgsConstructor
public class PurchaseService {

    private final PurchaseRepository purchaseRepository;
    private final IngredientService ingredientService;
    private final StockService stockService;

    public List<Purchase> findAll() {
        return purchaseRepository.findAllByOrderByCreatedAtDesc();
    }

    @Transactional
    public Purchase create(Long ingredientId, BigDecimal quantity, String supplier, BigDecimal totalPrice,
                            LocalDate purchaseDate, String createdBy) {
        if (supplier == null || supplier.isBlank()) {
            throw new IllegalArgumentException("Укажите поставщика");
        }
        Ingredient ingredient = ingredientService.findById(ingredientId);
        StockMovement movement = stockService.receive(ingredient, quantity, "Закупка у " + supplier.trim(), createdBy);

        Purchase purchase = new Purchase();
        purchase.setIngredient(ingredient);
        purchase.setQuantity(quantity);
        purchase.setSupplier(supplier.trim());
        purchase.setTotalPrice(totalPrice);
        purchase.setPurchaseDate(purchaseDate == null ? LocalDate.now() : purchaseDate);
        purchase.setCreatedBy(createdBy);
        purchase.setStockMovement(movement);
        return purchaseRepository.save(purchase);
    }
}
