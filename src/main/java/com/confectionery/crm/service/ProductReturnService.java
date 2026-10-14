package com.confectionery.crm.service;

import com.confectionery.crm.domain.Client;
import com.confectionery.crm.domain.Order;
import com.confectionery.crm.domain.OrderEventType;
import com.confectionery.crm.domain.OrderItem;
import com.confectionery.crm.domain.Product;
import com.confectionery.crm.domain.ProductReturn;
import com.confectionery.crm.domain.ProductReturnItem;
import com.confectionery.crm.repository.OrderRepository;
import com.confectionery.crm.repository.ProductReturnRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
    private final OneCQueue oneCQueue;
    private final CurrentUserService currentUserService;

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
    public ProductReturn create(Long clientId, List<ItemInput> items, LocalDate returnDate, String reason,
                                Long orderId, String createdBy) {
        ProductReturn productReturn = new ProductReturn();
        productReturn.setCreatedBy(createdBy);
        fill(productReturn, clientId, items, returnDate, reason);
        if (orderId != null) {
            productReturn.setOrder(findOrderOfSameClient(orderId, productReturn));
        }
        productReturn = productReturnRepository.save(productReturn);
        oneCQueue.productReturn(productReturn);
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
        oneCQueue.productReturn(productReturn);
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
            BigDecimal price = input.price() != null ? input.price() : clientPriceService.resolvePrice(client, product);
            // один и тот же товар по той же цене — одной строкой (и в 1С, и в накладной)
            ProductReturnItem same = productReturn.getItems().stream()
                    .filter(i -> i.getProduct().getId().equals(product.getId()) && i.getPrice().compareTo(price) == 0)
                    .findFirst()
                    .orElse(null);
            if (same != null) {
                same.setQuantity(same.getQuantity().add(input.quantity()));
                continue;
            }
            ProductReturnItem item = new ProductReturnItem();
            item.setProduct(product);
            item.setQuantity(input.quantity());
            item.setPrice(price);
            productReturn.addItem(item);
        }
    }

    /** Закрепить возврат за заказом — только за заказом того же клиента. */
    @Transactional
    public ProductReturn attach(Long returnId, Long orderId) {
        ProductReturn productReturn = findById(returnId);
        productReturn.setOrder(findOrderOfSameClient(orderId, productReturn));
        return productReturnRepository.save(productReturn);
    }

    private Order findOrderOfSameClient(Long orderId, ProductReturn productReturn) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Заказ не найден: " + orderId));
        if (!order.getClient().getId().equals(productReturn.getClient().getId())) {
            throw new IllegalArgumentException("Возврат другого клиента — закрепить можно только за заказом «"
                    + productReturn.getClient().getName() + "»");
        }
        return order;
    }

    @Transactional
    public ProductReturn detach(Long returnId) {
        ProductReturn productReturn = findById(returnId);
        productReturn.setOrder(null);
        return productReturnRepository.save(productReturn);
    }

    // ---------------------------------------------------------------- возврат к заказу (админка)

    /**
     * К каким заказам крепят возврат: дистрибьютор отдаёт собранный у магазинов возврат, когда забирает
     * заказ, — значит, заказ принят, собирается, готов к выдаче или уже выдан.
     */
    public static boolean acceptsReturn(Order order) {
        String b = order.getStatus().badge();
        return b.equals("new") || b.equals("work") || b.equals("ready") || b.equals("done");
    }

    /**
     * Возврат к заказу из админки: что привезли, в штуках. Весь товар негодный — сразу в списание (в 1С — склад
     * «Испорченные товары»), без приёмки и без причины; сумма возврата вычитается из заказа. Цена строки — цена
     * этого товара в заказе, если он в заказе есть, иначе цена клиента; у товаров, которые уже были в возврате,
     * цена прежняя. returnId == null — новый возврат, иначе — изменить этот (только возврат этого же заказа).
     * В историю заказа пишется, кто и что сделал.
     */
    @Transactional
    public ProductReturn saveForOrder(Long orderId, Long returnId, List<ItemInput> items, String createdBy) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Заказ не найден: " + orderId));
        if (!acceptsReturn(order)) {
            throw new IllegalStateException("К заказу в статусе «" + order.getStatus().badgeLabel()
                    + "» возврат не прикрепить: клиент отдаёт возврат, когда забирает заказ");
        }
        ProductReturn productReturn;
        String before = null;
        if (returnId == null) {
            productReturn = new ProductReturn();
            productReturn.setCreatedBy(createdBy);
            productReturn.setOrder(order);
        } else {
            productReturn = findById(returnId);
            if (productReturn.getOrder() == null || !productReturn.getOrder().getId().equals(orderId)) {
                throw new IllegalArgumentException("Возврат № " + returnId + " не от заказа № " + orderId);
            }
            before = describe(productReturn);
        }
        Map<Long, BigDecimal> kept = new HashMap<>();
        for (ProductReturnItem i : productReturn.getItems()) kept.putIfAbsent(i.getProduct().getId(), i.getPrice());
        List<ItemInput> priced = new ArrayList<>();
        for (ItemInput in : items) {
            BigDecimal price = in.price();
            if (price == null && in.productId() != null) price = kept.get(in.productId());
            if (price == null && in.productId() != null) price = orderPrice(order, in.productId());
            priced.add(new ItemInput(in.productId(), in.quantity(), price));   // null — цена клиента (см. fill)
        }
        LocalDate date = productReturn.getReturnDate() != null ? productReturn.getReturnDate() : LocalDate.now();
        fill(productReturn, order.getClient().getId(), priced, date, productReturn.getReason());
        productReturn = productReturnRepository.save(productReturn);
        if (returnId == null) order.getReturns().add(productReturn);
        String now = describe(productReturn);
        if (returnId == null) {
            OrderService.log(order, OrderEventType.RETURN, null, "Возврат № " + productReturn.getId() + ": " + now, actor());
        } else if (!now.equals(before)) {
            OrderService.log(order, OrderEventType.RETURN_EDIT, null,
                    "Возврат № " + productReturn.getId() + ": было " + before + ", стало " + now, actor());
        }
        oneCQueue.productReturn(productReturn);
        return productReturn;
    }

    /** Удалить возврат заказа: сначала в 1С (если она не ответила — возврат остаётся), потом запись в истории заказа. */
    @Transactional
    public void deleteForOrder(Long orderId, Long returnId) {
        ProductReturn productReturn = findById(returnId);
        Order order = productReturn.getOrder();
        if (order == null || !order.getId().equals(orderId)) {
            throw new IllegalArgumentException("Возврат № " + returnId + " не от заказа № " + orderId);
        }
        String what = describe(productReturn);
        oneCReturnSyncService.deleteOrThrow(productReturn);
        order.getReturns().remove(productReturn);
        productReturnRepository.delete(productReturn);
        OrderService.log(order, OrderEventType.RETURN_DELETE, null, "Возврат № " + returnId + ": " + what, actor());
    }

    /** Цена товара в этом заказе; null — товара в заказе нет. */
    public static BigDecimal orderPrice(Order order, Long productId) {
        for (OrderItem i : order.getItems()) {
            if (i.getProduct().getId().equals(productId)) return i.getPrice();
        }
        return null;
    }

    /** Штук в возврате. */
    public static BigDecimal pieces(ProductReturn productReturn) {
        return productReturn.getItems().stream().map(ProductReturnItem::getQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** «16 шт на 30 550 ₸» — для истории заказа. */
    static String describe(ProductReturn productReturn) {
        DecimalFormatSymbols sym = new DecimalFormatSymbols(new Locale("ru"));
        sym.setGroupingSeparator('\u00A0');
        sym.setDecimalSeparator(',');
        return new DecimalFormat("#,##0.###", sym).format(pieces(productReturn)) + "\u00A0шт на "
                + new DecimalFormat("#,##0", sym).format(productReturn.getTotalSum()) + "\u00A0₸";
    }

    private String actor() {
        String name = currentUserService.displayName();
        return name != null ? name : "Сотрудник";
    }

    /** Повторная ручная выгрузка в 1С — см. OneCReturnSyncService.syncOrThrow. */
    @Transactional
    public ProductReturn resyncOneC(Long id) {
        ProductReturn productReturn = findById(id);
        // Через очередь — см. ProductionRequestService.resyncOneC (иначе возможен дубль в 1С).
        productReturn.setOneCSyncError(null);
        oneCQueue.productReturn(productReturn);
        return productReturn;
    }
}
