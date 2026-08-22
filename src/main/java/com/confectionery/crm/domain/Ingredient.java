package com.confectionery.crm.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(name = "ingredients")
@Getter
@Setter
@NoArgsConstructor
public class Ingredient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Укажите название ингредиента")
    @Column(nullable = false, unique = true)
    private String name;

    @NotBlank(message = "Укажите единицу измерения")
    @Column(nullable = false)
    private String unit; // кг, г, л, мл, шт...

    @NotNull
    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal quantityInStock = BigDecimal.ZERO;

    @NotNull
    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal minThreshold = BigDecimal.ZERO;

    public boolean isLowStock() {
        return quantityInStock.compareTo(minThreshold) < 0;
    }
}
