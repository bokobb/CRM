package com.confectionery.crm.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Сотрудник цеха (не пользователь приложения — у рабочих нет входа). Технолог каждый день
 * распределяет их по бригадам заявок на сырьё ({@link ProductionBrigadeMember}); ставка за смену
 * идёт в себестоимость заявки. Ставку видят и меняют только ADMIN/ACCOUNTANT.
 * Не удаляется, а увольняется (active=false) — иначе потеряется история бригад.
 */
@Entity
@Table(name = "employees")
@Getter
@Setter
@NoArgsConstructor
public class Employee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String fullName;

    private String position;

    private String phone;

    /** ₸ за смену (день); null — ставку ещё не задали, в себестоимость идёт 0. */
    @Column(precision = 12, scale = 2)
    private BigDecimal dailyRate;

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
