package com.confectionery.crm.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Возврат испорченного / просроченного товара от клиента. Создаётся с нуля (не "на основании"
 * заказа) — товар мог быть продан когда угодно раньше, поэтому ни состав, ни количество с
 * заказом не сверяются. Уже после создания возврат можно закрепить за одним из заказов того же
 * клиента ({@link #order}) — тогда в карточке заказа видно "Заказ − Возврат = Итого" (см.
 * {@link Order#getNetTotal()}).
 *
 * <p>На склад готовой продукции CRM не возвращается — в 1С уходит документом "Возврат товаров
 * от покупателя" сразу на склад "Испорченные товары" и там же проводится (см.
 * OneCReturnSyncService). Остатки CRM этот склад не видят (см. OneCStockSyncService).
 */
@Entity
@Table(name = "product_returns")
@Getter
@Setter
@NoArgsConstructor
public class ProductReturn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "client_id", nullable = false)
    private Client client;

    /** Заказ, за которым закреплён возврат — null, пока не закрепили (см. ProductReturnService.attach). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id")
    private Order order;

    @Column(nullable = false)
    private LocalDate returnDate;

    /** Причина свободным текстом ("Испорчен", "Истёк срок" и т.п.) — намеренно не enum, см. CHECK-constraint на enum-колонках. */
    private String reason;

    @Column(nullable = false)
    private String createdBy;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    /** Документ "Возврат товаров от покупателя" в 1С — см. OneCReturnSyncService. */
    private String oneCDocumentKey;
    private String oneCDocumentNumber;
    @Column(length = 500)
    private String oneCSyncError;

    @OneToMany(mappedBy = "productReturn", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ProductReturnItem> items = new ArrayList<>();

    public void addItem(ProductReturnItem item) {
        item.setProductReturn(this);
        items.add(item);
    }

    public BigDecimal getTotalSum() {
        return items.stream()
                .map(i -> i.getPrice().multiply(i.getQuantity()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
