package com.confectionery.crm.service;

import com.confectionery.crm.domain.Client;
import com.confectionery.crm.domain.Order;
import com.confectionery.crm.domain.Product;
import com.confectionery.crm.domain.ProductReturn;
import com.confectionery.crm.domain.ProductReturnItem;
import com.confectionery.crm.repository.OrderRepository;
import com.confectionery.crm.repository.ProductReturnRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Возвраты испорченного / просроченного товара — см. {@link ProductReturn}. Создаются с нуля,
 * сразу выгружаются в 1С на склад "Испорченные товары" (см. OneCReturnSyncService); за заказом
 * закрепляются отдельным действием из карточки заказа (см. {@link #attach}).
 */
@Service
@RequiredArgsConstructor
public class ProductReturnService {

    private final ProductReturnRepository productReturnRepository;
    private final OrderRepository orderRepository;
    private final ClientService clientService;
    private final ProductService productService;
    private final ClientPriceService clientPriceService;
    private final OneCReturnSyncService oneCReturnSyncService;

    /** price == null — берётся цена клиента (см. ClientPriceService.resolvePrice). */
    public record ItemInput(Long productId, BigDecimal quantity, BigDecimal price) {
    }

    public List<ProductReturn> findAll() {
        return productReturnRepository.findAllByOrderByCreatedAtDesc();
    }

    public List<ProductReturn> findByDateRange(LocalDate from, LocalDate to) {
        return productReturnRepository.findByReturnDateBetweenOrderByCreatedAtDesc(from, to);
    }

    public List<ProductReturn> findByOrder(Long orderId) {
        return productReturnRepository.findByOrderIdOrderByCreatedAtDesc(orderId);
    }

    public List<ProductReturn> findUnattached(Long clientId) {
        return productReturnRepository.findByClientIdAndOrderIsNullOrderByCreatedAtDesc(clientId);
    }

    public ProductReturn findById(Long id) {
        return productReturnRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Возврат не найден: " + id));
    }

    @Transactional
    public ProductReturn create(Long clientId, List<ItemInput> items, LocalDate returnDate, String reason, String createdBy) {
        ProductReturn productReturn = new ProductReturn();
        productReturn.setCreatedBy(createdBy);
        fill(productReturn, clientId, items, returnDate, reason);
        productReturn = productReturnRepository.save(productReturn);
        oneCReturnSyncService.trySync(productReturn);
        return productReturn;
    }

    /**
     * Правка возврата: состав, клиент, дата, причина. В 1С тот же документ распроводится,
     * перезаписывается и проводится заново (см. OneCReturnSyncService.sync). Если сменился
     * клиент — возврат открепляется от заказа прежнего клиента.
     */
    @Transactional
    public ProductReturn update(Long id, Long clientId, List<ItemInput> items, LocalDate returnDate, String reason) {
        ProductReturn productReturn = findById(id);
        fill(productReturn, clientId, items, returnDate, reason);
        if (productReturn.getOrder() != null
                && !productReturn.getOrder().getClient().getId().equals(productReturn.getClient().getId())) {
            productReturn.setOrder(null);
        }
        productReturn = productReturnRepository.save(productReturn);
        oneCReturnSyncService.trySync(productReturn);
        return productReturn;
    }

    /**
     * Удаление: сначала в 1С (распровести + пометить на удаление, см.
     * OneCReturnSyncService.deleteOrThrow) — если 1С не ответила, в CRM возврат остаётся.
     */
    @Transactional
    public void delete(Long id) {
        ProductReturn productReturn = findById(id);
        oneCReturnSyncService.deleteOrThrow(productReturn);
        productReturnRepository.delete(productReturn);
    }

    private void fill(ProductReturn productReturn, Long clientId, List<ItemInput> items, LocalDate returnDate, String reason) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("Добавьте хотя бы один товар");
        }
        Client client = clientService.findById(clientId);
        productReturn.setClient(client);
        productReturn.setReturnDate(returnDate == null ? LocalDate.now() : returnDate);
        productReturn.setReason(reason == null || reason.isBlank() ? null : reason.trim());

        productReturn.getItems().clear();
        for (ItemInput input : items) {
            if (input.productId() == null) {
                throw new IllegalArgumentException("Выберите товар в каждой позиции");
            }
            if (input.quantity() == null || input.quantity().signum() <= 0) {
                throw new IllegalArgumentException("Количество должно быть больше нуля");
            }
            if (input.price() != null && input.price().signum() < 0) {
                throw new IllegalArgumentException("Цена не может быть отрицательной");
            }
            Product product = productService.findById(input.productId());
            ProductReturnItem item = new ProductReturnItem();
            item.setProduct(product);
            item.setQuantity(input.quantity());
            item.setPrice(input.price() != null ? input.price() : clientPriceService.resolvePrice(client, product));
            productReturn.addItem(item);
        }
    }

    /** Закрепить возврат за заказом — только за заказом того же клиента. */
    @Transactional
    public ProductReturn attach(Long returnId, Long orderId) {
        ProductReturn productReturn = findById(returnId);
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Заказ не найден: " + orderId));
        if (!order.getClient().getId().equals(productReturn.getClient().getId())) {
            throw new IllegalArgumentException("Возврат другого клиента — закрепить можно только за заказом «"
                    + productReturn.getClient().getName() + "»");
        }
        productReturn.setOrder(order);
        return productReturnRepository.save(productReturn);
    }

    @Transactional
    public ProductReturn detach(Long returnId) {
        ProductReturn productReturn = findById(returnId);
        productReturn.setOrder(null);
        return productReturnRepository.save(productReturn);
    }

    /** Повторная ручная выгрузка в 1С — см. OneCReturnSyncService.syncOrThrow. */
    @Transactional
    public ProductReturn resyncOneC(Long id) {
        ProductReturn productReturn = findById(id);
        oneCReturnSyncService.syncOrThrow(productReturn);
        return productReturn;
    }
}
