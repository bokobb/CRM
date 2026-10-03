package com.confectionery.crm.domain;

import org.hibernate.annotations.DynamicUpdate;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Закупка сырья у контрагента — один контрагент может привезти сразу несколько разных товаров
 * за один раз (см. {@link PurchaseItem}), поэтому Purchase — только "шапка" (контрагент, дата,
 * фото накладной), сами товары/количества/суммы — в items. Каждая позиция двигает остаток
 * отдельно (см. StockService.receive в PurchaseService.create) — само движение делает
 * StockService, Purchase лишь хранит закупочные подробности и ссылается на созданные
 * StockMovement для трассируемости.
 *
 * <p>Вся закупка целиком выгружается в 1С одним документом "Поступление товаров и услуг" (см.
 * OneCPurchaseSyncService) — Counterparty обязателен именно поэтому (нужен Контрагент_Key).
 */
@Entity
@DynamicUpdate
@Table(name = "purchases")
@Getter
@Setter
@NoArgsConstructor
public class Purchase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "counterparty_id", nullable = false)
    private Counterparty counterparty;

    @Column(nullable = false)
    private LocalDate purchaseDate;

    @Column(nullable = false)
    private String createdBy;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    /** Фото накладной от поставщика (см. FileStorageService.storePurchaseInvoiceImage) — необязательно, одно на всю закупку. */
    @Column(length = 2000)
    private String imageUrl;

    /**
     * Документ "Поступление товаров и услуг" в 1С — НЕпроведённый (бухгалтер проверяет и
     * проводит сам, как и заказы), см. OneCPurchaseSyncService. oneCSyncError заполняется
     * вместо номера, если выгрузка не удалась (например, контрагент не привязан к 1С).
     */
    private String oneCDocumentKey;
    private String oneCDocumentNumber;
    private String oneCSyncError;

    /**
     * Документ(ы) в 1С нужно привести к состоянию в CRM — этим занимается фоновая очередь
     * (OneCQueue), пользователь 1С не ждёт.
     */
    @Column(name = "onec_pending", nullable = false, columnDefinition = "boolean default false")
    private boolean oneCPending;

    @OneToMany(mappedBy = "purchase", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PurchaseItem> items = new ArrayList<>();

    public void addItem(PurchaseItem item) {
        item.setPurchase(this);
        items.add(item);
    }

    /** Сумма по всем позициям — позиции без указанной цены (totalPrice == null) в сумму не входят. */
    public BigDecimal getTotalSum() {
        return items.stream()
                .map(PurchaseItem::getTotalPrice)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
