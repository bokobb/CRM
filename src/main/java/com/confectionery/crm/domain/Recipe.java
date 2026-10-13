package com.confectionery.crm.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Рецептура товара: сколько сырья нужно на партию (yieldQuantity штук/единиц товара).
 */
@Entity
@Table(name = "recipes")
@Getter
@Setter
@NoArgsConstructor
public class Recipe {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false, unique = true)
    private Product product;

    @NotNull
    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal yieldQuantity = BigDecimal.ONE;

    /** Кто и когда последним сохранил рецептуру (админка «Рецепты», приложение, импорт из 1С). */
    private java.time.LocalDateTime updatedAt;
    private String updatedBy;

    /** В порядке ввода — при импорте из 1С это порядок строк спецификации (удобно складу). */
    @OneToMany(mappedBy = "recipe", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<RecipeItem> items = new ArrayList<>();

    public void addItem(RecipeItem item) {
        item.setRecipe(this);
        items.add(item);
    }
}
