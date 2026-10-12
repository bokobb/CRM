package com.confectionery.crm.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Вакансия на странице сайта /vacancies. Ведётся в приложении (меню «Вакансии», ADMIN/ACCOUNTANT).
 * Три списка — текст, один пункт в строке. Вакансию с откликами не удаляют, а скрывают (active = false).
 */
@Entity
@Table(name = "vacancies")
@Getter
@Setter
@NoArgsConstructor
public class Vacancy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 80)
    private String title;

    /** Строка под названием, можно пусто. */
    @Column(length = 140)
    private String summary;

    /** «5/2, 8:00–17:00», «2/2, по 12 часов». */
    @Column(nullable = false, length = 60)
    private String schedule;

    /** «от 1 года», «не нужен, научим». */
    @Column(nullable = false, length = 60)
    private String experience;

    /** Текстом: «от 250 000 ₸». Пусто — на сайте «по договорённости». */
    @Column(length = 60)
    private String salary;

    /** «Что делать». */
    @Column(columnDefinition = "TEXT")
    private String duties;

    /** «Кого ищем». */
    @Column(columnDefinition = "TEXT")
    private String requirements;

    /** «Что предлагаем». */
    @Column(columnDefinition = "TEXT")
    private String conditions;

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false)
    private int sortOrder;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();
}
