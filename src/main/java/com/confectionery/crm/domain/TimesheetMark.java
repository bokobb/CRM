package com.confectionery.crm.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Отметка табеля за день (меню «Отчёты» → «Табель сотрудников»), поставленная вручную
 * бухгалтером/технологом. Нет отметки — табель берёт день из бригад заявок на сырьё (был в
 * бригаде — пришёл), воскресенье — выходной, иначе пусто (см. TimesheetService).
 * status — строка (P/H/A/O; H — полдня (отпросился), 0,5 дня), не enum: см. CHECK-constraint на enum-колонках.
 */
@Entity
@Table(name = "timesheet_marks", uniqueConstraints = @UniqueConstraint(columnNames = {"employee_id", "work_date"}))
@Getter
@Setter
@NoArgsConstructor
public class TimesheetMark {

    public static final String PRESENT = "P", HALF = "H", ABSENT = "A", DAY_OFF = "O";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id")
    private Employee employee;

    @Column(name = "work_date", nullable = false)
    private LocalDate date;

    @Column(nullable = false, length = 1)
    private String status;

    private String updatedBy;

    private LocalDateTime updatedAt = LocalDateTime.now();
}
