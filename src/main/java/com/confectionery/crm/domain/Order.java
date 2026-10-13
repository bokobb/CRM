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

@Entity
@DynamicUpdate
@Table(name = "orders")
@Getter
@Setter
@NoArgsConstructor
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "client_id", nullable = false)
    private Client client;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private OrderStatus status = OrderStatus.NEW;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDate dueDate;

    @Column(length = 2000)
    private String notes;

    // Заполняются складом ГП при отгрузке (см. OrderService.ship)
    private String vehicleNumber;
    private LocalDateTime shippedAt;
    private String shippedByName;

    /**
     * Долг по оплате (проставляет бухгалтер вместо/наряду с "Завершить заказ", если клиент
     * ещё не оплатил). debtDueDate == null — долга нет. Оба поля намеренно nullable без
     * значения по умолчанию — колонки добавляются поверх уже существующей таблицы с данными,
     * а NOT NULL там, где нет данных, требует отдельного backfill (см. ScheduledJobs.checkOverdueDebts).
     */
    private LocalDate debtDueDate;
    private Boolean debtPaid;

    /** Заказ пришёл автоматически из WhatsApp (см. WhatsAppOrderIntakeService) — для иконки в списках. */
    private Boolean fromWhatsapp;

    /**
     * Кто завёл заказ: клиент сам (сайт, WhatsApp) или сотрудник в CRM — история заказов на /shop
     * по нему решает, с какого этапа начинать дорожку («Новая» есть только у заказов клиента).
     * null — старый заказ, по которому не видно, кто его создал.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private OrderSource source;

    /** Когда заказ принят (PLACED -> NEW); у заказа сотрудника — время создания. */
    private LocalDateTime acceptedAt;

    /** Когда склад ГП отметил заказ собранным (NEW -> READY). */
    private LocalDateTime readyAt;

    /**
     * Окно выдачи: час начала (8 = 08:00–09:00) в день dueDate. Пока окна — заглушка: часы и
     * вместимость из настроек (crm.pickup.*, см. PickupSlotService). null — время не выбрано
     * (заказы из приложения, 1С, старые).
     */
    private Integer pickupHour;

    /** Способ оплаты; null — не выбран (старые заказы, сайт без выбора оплаты). */
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private PaymentMethod paymentMethod;

    /** Оплату отметил сотрудник (директор, бухгалтер) — см. OrderService.markPaid. */
    private LocalDateTime paidAt;
    private String paidByName;

    /** Журнал действий по заказу (кто, когда) — удаляется вместе с заказом. */
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("at ASC, id ASC")
    private List<OrderEvent> events = new ArrayList<>();

    public boolean isPaid() {
        return paidAt != null || Boolean.TRUE.equals(debtPaid) || status == OrderStatus.COMPLETED;
    }

    /**
     * Номер и GUID документа "Реализация товаров и услуг", созданного НЕпроведённым в 1С при
     * оформлении заказа (см. OneCOrderSyncService) — бухгалтер потом проверяет и проводит его
     * вручную в 1С. oneCSyncError заполняется вместо номера, если выгрузка не удалась
     * (например, клиент не привязан к 1С) — админ видит причину через уведомление и здесь.
     */
    private String oneCDocumentNumber;
    private String oneCDocumentKey;
    @Column(length = 500)
    private String oneCSyncError;

    /**
     * Документ(ы) в 1С нужно привести к состоянию в CRM — этим занимается фоновая очередь
     * (OneCQueue), пользователь 1С не ждёт.
     */
    @Column(name = "onec_pending", nullable = false, columnDefinition = "boolean default false")
    private boolean oneCPending;

    /**
     * Заказ заведён импортом из документа, созданного бухгалтером прямо в 1С (OneCOrderImportService).
     * Такой документ CRM не перезаписывает целиком (шапка — бухгалтера), а только проводит, и
     * правит строки «Товары», если состав изменили в CRM (см. OneCOrderSyncService.syncImported).
     */
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean importedFromOneC;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    /** Возвраты, закреплённые за заказом (см. ProductReturn.order) — без каскада, возврат живёт сам по себе. */
    @OneToMany(mappedBy = "order")
    private List<ProductReturn> returns = new ArrayList<>();

    public void addItem(OrderItem item) {
        item.setOrder(this);
        items.add(item);
    }

    public BigDecimal getTotalPrice() {
        return items.stream()
                .map(i -> i.getPrice().multiply(i.getQuantity()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public BigDecimal getReturnTotal() {
        return returns.stream()
                .map(ProductReturn::getTotalSum)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Фактическая сумма заказа: заказано минус закреплённые возвраты. */
    public BigDecimal getNetTotal() {
        return getTotalPrice().subtract(getReturnTotal());
    }
}
