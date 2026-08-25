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

@Entity
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

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ProductionTask> productionTasks = new ArrayList<>();

    public void addItem(OrderItem item) {
        item.setOrder(this);
        items.add(item);
    }

    public BigDecimal getTotalPrice() {
        return items.stream()
                .map(i -> i.getPrice().multiply(i.getQuantity()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
