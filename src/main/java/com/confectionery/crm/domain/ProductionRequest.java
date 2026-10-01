package com.confectionery.crm.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
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

    /**
     * Дата документа для 1С (см. OneCProductionSyncService.sync) — по умолчанию сегодня, но
     * пользователь может выбрать другую при создании/редактировании заявки (например, заявку
     * оформили сегодня на вчерашнюю смену). Время в документах 1С всегда фиксированное 06:00,
     * этим полем задаётся только дата.
     */
    @Column(nullable = false)
    private LocalDate documentDate = LocalDate.now();

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

    /**
     * Кнопка "Завершить" нажата и требование-накладная проведена в 1С — дальше состав сырья
     * менять нельзя (редактировать/удалять/добавлять позиции скрыто в UI), пока не нажали
     * "Отменить" (см. ProductionRequestService.cancelCompletion — распроводит документ в 1С и
     * снова разрешает править состав).
     */
    private boolean requisitionCompleted = false;

    @OneToMany(mappedBy = "request", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ProductionRequestLine> lines = new ArrayList<>();

    @OneToMany(mappedBy = "request", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ProductionRequestItem> items = new ArrayList<>();

    /** Бригада — кто работал на заявке в её день (экран «Производство», ProductionBrigadeService). */
    @OneToMany(mappedBy = "request", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ProductionBrigadeMember> brigade = new ArrayList<>();

    /** История состава сырья после выдачи работникам — см. {@link ProductionRequestRevision}. */
    @OneToMany(mappedBy = "request", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("createdAt ASC, id ASC")
    private List<ProductionRequestRevision> revisions = new ArrayList<>();

    /**
     * Состав изменился после выдачи и разницу ещё не выдали/не вернули — подсказка в списке
     * заявок. Boolean, а не boolean: колонка добавляется в уже заполненную таблицу, null = нет.
     */
    private Boolean compositionDiffPending;

    public boolean isCompositionDiffPending() {
        return Boolean.TRUE.equals(compositionDiffPending);
    }

    public void addLine(ProductionRequestLine line) {
        line.setRequest(this);
        lines.add(line);
    }

    public void addItem(ProductionRequestItem item) {
        item.setRequest(this);
        items.add(item);
    }
}
