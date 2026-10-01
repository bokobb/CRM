package com.confectionery.crm.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
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

    /** ₸ за смену (день); null — берётся месячный оклад ÷ рабочие дни месяца (см. SalaryFundService). */
    @Column(precision = 12, scale = 2)
    private BigDecimal dailyRate;

    @Column(nullable = false)
    private boolean active = true;

    /** Оклад в месяц, ₸ (из 1С, «Оклад по дням»). */
    @Column(precision = 12, scale = 2)
    private BigDecimal monthlySalary;

    /** Группа затрат; null — по должности ({@link EmployeeCostGroup#byPosition}). */
    @Enumerated(EnumType.STRING)
    private EmployeeCostGroup costGroup;

    /** Ref_Key элемента справочника «Сотрудники» в 1С — null у заведённых только в CRM (см. OneCEmployeeImportService). */
    @Column(unique = true)
    private String oneCKey;

    /** ИИН физлица из 1С. */
    private String iin;

    /** Дата приёма на работу из 1С. */
    private LocalDate hiredAt;

    /** Дата рождения из 1С. */
    private LocalDate birthDate;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public EmployeeCostGroup effectiveCostGroup() {
        return costGroup != null ? costGroup : EmployeeCostGroup.byPosition(position);
    }
}
