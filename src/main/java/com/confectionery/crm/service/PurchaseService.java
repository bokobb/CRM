package com.confectionery.crm.service;

import com.confectionery.crm.domain.Counterparty;
import com.confectionery.crm.domain.Ingredient;
import com.confectionery.crm.domain.Purchase;
import com.confectionery.crm.domain.PurchaseItem;
import com.confectionery.crm.domain.StockMovement;
import com.confectionery.crm.repository.PurchaseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Закупка сырья у контрагента — обёртка над StockService.receive (по одному вызову на каждую
 * позицию, см. {@link ItemInput}) с дополнительными закупочными подробностями (контрагент,
 * даты, фото накладной), которых нет в обычном приходе (см. WarehouseMovementController).
 */
@Service
@RequiredArgsConstructor
public class PurchaseService {

    private final PurchaseRepository purchaseRepository;
    private final IngredientService ingredientService;
    private final CounterpartyService counterpartyService;
    private final StockService stockService;
    private final FileStorageService fileStorageService;
    private final OneCPurchaseSyncService oneCPurchaseSyncService;

    /** Одна позиция закупки: конкретное сырьё + количество + сумма (необязательно). */
    public record ItemInput(Long ingredientId, BigDecimal quantity, BigDecimal totalPrice) {
    }

    public List<Purchase> findAll() {
        return purchaseRepository.findAllByOrderByCreatedAtDesc();
    }

    public List<Purchase> findByPurchaseDateBetween(LocalDate from, LocalDate to) {
        return purchaseRepository.findByPurchaseDateBetweenOrderByCreatedAtDesc(from, to);
    }

    public Purchase findById(Long id) {
        return purchaseRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Закупка не найдена: " + id));
    }

    /** Повторная ручная выгрузка в 1С — см. OneCPurchaseSyncService.syncOrThrow. */
    @Transactional
    public Purchase resyncOneC(Long id) {
        Purchase purchase = findById(id);
        oneCPurchaseSyncService.syncOrThrow(purchase);
        return purchase;
    }

    @Transactional
    public Purchase create(Long counterpartyId, List<ItemInput> items, LocalDate purchaseDate, String createdBy) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("Добавьте хотя бы один товар");
        }
        Counterparty counterparty = counterpartyService.findById(counterpartyId);

        Purchase purchase = new Purchase();
        purchase.setCounterparty(counterparty);
        purchase.setPurchaseDate(purchaseDate == null ? LocalDate.now() : purchaseDate);
        purchase.setCreatedBy(createdBy);

        for (ItemInput input : items) {
            if (input.ingredientId() == null) {
                throw new IllegalArgumentException("Выберите товар в каждой позиции");
            }
            if (input.quantity() == null || input.quantity().signum() <= 0) {
                throw new IllegalArgumentException("Количество должно быть больше нуля");
            }
            Ingredient ingredient = ingredientService.findById(input.ingredientId());
            StockMovement movement = stockService.receive(ingredient, input.quantity(),
                    "Закупка у " + counterparty.getName(), createdBy);

            PurchaseItem item = new PurchaseItem();
            item.setIngredient(ingredient);
            item.setQuantity(input.quantity());
            item.setTotalPrice(input.totalPrice());
            item.setStockMovement(movement);
            purchase.addItem(item);
        }

        purchase = purchaseRepository.save(purchase);
        oneCPurchaseSyncService.trySyncPurchase(purchase);
        return purchase;
    }

    /** Фото накладной поставщика, снятое на телефон при оформлении закупки — одно на всю закупку. */
    public Purchase attachImage(Long id, MultipartFile file) {
        Purchase purchase = findById(id);
        purchase.setImageUrl(fileStorageService.storePurchaseInvoiceImage(id, file));
        return purchaseRepository.save(purchase);
    }
}
