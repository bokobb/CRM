package com.confectionery.crm.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(name = "products")
@Getter
@Setter
@NoArgsConstructor
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Укажите название товара")
    @Column(nullable = false)
    private String name;

    private String category;

    @Column(length = 1000)
    private String description;

    /** Единица измерения (шт, кг и т.д.) — для складского учёта и накладной. */
    private String unit;

    /** Ссылка на фото товара (внешний URL) — показывается в мобильном приложении при оформлении заказа. */
    @Column(length = 2000)
    private String imageUrl;

    @NotNull
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price = BigDecimal.ZERO;

    /**
     * Остаток готовой продукции на складе ГП. Не помечен nullable=false в БД специально:
     * колонка добавлена поверх уже существующей таблицы с данными (ddl-auto=update не умеет
     * задавать DEFAULT при ALTER TABLE ... NOT NULL на непустой таблице) — обязательность
     * гарантируется на уровне Java (значение по умолчанию ниже) и явным backfill существующих строк.
     */
    @NotNull
    @Column(precision = 12, scale = 3)
    private BigDecimal quantityInStock = BigDecimal.valueOf(1000);

    @OneToOne(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private Recipe recipe;
}
