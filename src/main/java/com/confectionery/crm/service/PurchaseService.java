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
        validate(items);
        Counterparty counterparty = counterpartyService.findById(counterpartyId);

        Purchase purchase = new Purchase();
        purchase.setCounterparty(counterparty);
        purchase.setPurchaseDate(purchaseDate == null ? LocalDate.now() : purchaseDate);
        purchase.setCreatedBy(createdBy);
        addItems(purchase, items, createdBy);

        purchase = purchaseRepository.save(purchase);
        oneCPurchaseSyncService.trySyncPurchase(purchase);
        return purchase;
    }

    /**
     * Правка закупки (свайп карточки вправо): поставщик, дата, позиции. Старый приход сырья
     * откатывается, новый проводится заново; документ «Поступление» в 1С распроводится,
     * перезаписывается и проводится снова (OneCPurchaseSyncService.sync). Неудача в 1С правку
     * в CRM не отменяет — причина в oneCSyncError, повтор — кнопкой «Отправить в 1С».
     */
    @Transactional
    public Purchase update(Long id, Long counterpartyId, List<ItemInput> items, LocalDate purchaseDate, String by) {
        validate(items);
        Purchase purchase = findById(id);
        List<StockMovement> oldMovements = purchase.getItems().stream()
                .map(PurchaseItem::getStockMovement).filter(java.util.Objects::nonNull).toList();
        purchase.getItems().clear();
        purchaseRepository.saveAndFlush(purchase);
        oldMovements.forEach(stockService::revertReceipt);

        purchase.setCounterparty(counterpartyService.findById(counterpartyId));
        if (purchaseDate != null) purchase.setPurchaseDate(purchaseDate);
        addItems(purchase, items, by);
        purchase = purchaseRepository.save(purchase);
        oneCPurchaseSyncService.trySyncPurchase(purchase);
        return purchase;
    }

    private static void validate(List<ItemInput> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("Добавьте хотя бы один товар");
        }
    }

    private void addItems(Purchase purchase, List<ItemInput> items, String createdBy) {
        Counterparty counterparty = purchase.getCounterparty();
        for (ItemInput input : items) {
            if (input.ingredientId() == null) {
                throw new IllegalArgumentException("Выберите товар в каждой позиции");
            }
            if (input.quantity() == null || input.quantity().signum() <= 0) {
                throw new IllegalArgumentException("Количество должно быть больше нуля");
            }
            Ingredient ingredient = ingredientService.requireActive(ingredientService.findById(input.ingredientId()));
            StockMovement movement = stockService.receive(ingredient, input.quantity(),
                    "Закупка у " + counterparty.getName(), createdBy);

            PurchaseItem item = new PurchaseItem();
            item.setIngredient(ingredient);
            item.setQuantity(input.quantity());
            item.setTotalPrice(input.totalPrice());
            item.setStockMovement(movement);
            purchase.addItem(item);
        }
    }

    /** Фото накладной поставщика, снятое на телефон при оформлении закупки — одно на всю закупку. */
    public Purchase attachImage(Long id, MultipartFile file) {
        Purchase purchase = findById(id);
        purchase.setImageUrl(fileStorageService.storePurchaseInvoiceImage(id, file));
        return purchaseRepository.save(purchase);
    }
}
