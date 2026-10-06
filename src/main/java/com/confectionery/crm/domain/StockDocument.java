package com.confectionery.crm.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Один ручной документ склада — то, что зав. складом оформил одной формой «Поступление»/
 * «Списание» (несколько позиций разом). В 1С ему соответствует один проведённый документ:
 * «Оприходование товаров» или «Списание товаров» (см. OneCStockDocumentSyncService). Товар и
 * сырьё — разные документы (разные склады в 1С), смешанных не бывает.
 *
 * <p>Отправка в 1С — через общую фоновую очередь (флаг {@link #oneCPending}, см. OneCQueue).
 * Удаление строки в CRM перепроводит документ в 1С без неё; удаление последней — помечает
 * документ 1С на удаление.</p>
 */
@Entity
@Table(name = "stock_documents")
@Getter
@Setter
@NoArgsConstructor
@DynamicUpdate
public class StockDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StockMovementType type;

    /** true — готовая продукция (склад ГП), false — сырьё (склад материалов). */
    @Column(nullable = false)
    private boolean product;

    @Column(nullable = false)
    private LocalDate documentDate;

    private String reason;

    @Column(nullable = false)
    private String createdBy;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @OneToMany(mappedBy = "document")
    private List<StockMovement> movements = new ArrayList<>();

    private String oneCDocumentKey;

    private String oneCDocumentNumber;

    @Column(length = 2000)
    private String oneCSyncError;

    @Column(nullable = false)
    private boolean oneCPending;
}
