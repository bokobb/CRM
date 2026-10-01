package com.confectionery.crm.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Сотрудник в бригаде заявки на сырьё — кто работал на этой заявке в её день (documentDate).
 * Один сотрудник за день — только в одной бригаде (проверяет ProductionBrigadeService).
 * Оплата — текущая ставка сотрудника за смену (Employee.dailyRate).
 */
@Entity
@Table(name = "production_brigade_members",
        uniqueConstraints = @UniqueConstraint(columnNames = {"request_id", "employee_id"}))
@Getter
@Setter
@NoArgsConstructor
public class ProductionBrigadeMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "request_id", nullable = false)
    private ProductionRequest request;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;
}
