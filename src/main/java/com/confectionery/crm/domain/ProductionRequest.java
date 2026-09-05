package com.confectionery.crm.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Заявка на производство: одна заявка может покрывать НЕСКОЛЬКО товаров сразу (см.
 * {@link ProductionRequestLine}) — например, из одного теста в один день делают несколько
 * разных упакованных вариантов. Жизненный цикл: NEW (создана) -> COLLECTED (зав. склада
 * сырья собрал общий список сырья по всем строкам и списал со своего склада) -> RECEIVED
 * (когда КАЖДАЯ строка оприходована зав. складом ГП — у каждого товара свой фактический выход,
 * поэтому оприходование идёт по одной строке за раз, см. {@link ProductionRequestLine}).
 * items — общий список сырья (снимок RecipeItem.quantity, просуммированный по всем строкам),
 * не привязан к живым Recipe — правки рецептуры задним числом не влияют на уже созданные заявки.
 */
@Entity
@Table(name = "production_requests")
@Getter
@Setter
@NoArgsConstructor
public class ProductionRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Название заявки — задаёт пользователь при создании (например, "Коржик"). */
    @Column(nullable = false)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ProductionRequestStatus status = ProductionRequestStatus.NEW;

    @Column(nullable = false)
    private String createdBy;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private String collectedBy;
    private LocalDateTime collectedAt;

    /**
     * Когда заявка полностью оприходована (все строки), CRM сама выгружает её в 1С парой
     * связанных документов — "Отчёт производства за смену" (готовая продукция) и "Требование-
     * накладная" (списание сырья, ссылается на отчёт через ДокументОснование) — см.
     * OneCProductionSyncService. Оба создаются НЕпроведёнными, бухгалтер проверяет и проводит
     * сам (как и заказы) — плановая себестоимость строк отчёта всегда 0, её вписывают вручную.
     */
    private String oneCReportKey;
    private String oneCReportNumber;
    private String oneCRequisitionKey;
    private String oneCRequisitionNumber;
    @Column(length = 500)
    private String oneCSyncError;

    @OneToMany(mappedBy = "request", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ProductionRequestLine> lines = new ArrayList<>();

    @OneToMany(mappedBy = "request", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ProductionRequestItem> items = new ArrayList<>();

    public void addLine(ProductionRequestLine line) {
        line.setRequest(this);
        lines.add(line);
    }

    public void addItem(ProductionRequestItem item) {
        item.setRequest(this);
        items.add(item);
    }
}
