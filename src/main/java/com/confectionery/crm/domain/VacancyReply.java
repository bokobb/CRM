package com.confectionery.crm.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Отклик с сайта (/vacancies). vacancyId пусто — кадровый резерв («Оставить номер»). Название вакансии
 * копируется на момент отклика, чтобы отклик не терял смысл, когда вакансию переименуют или скроют.
 * Хранится полгода (VacancyReplyService.purgeOld) — в нём персональные данные.
 *
 * <p>Статус строкой без enum — как в ShopAccessRequest, без CHECK-constraint от Hibernate.</p>
 */
@Entity
@Table(name = "vacancy_replies")
@Getter
@Setter
@NoArgsConstructor
public class VacancyReply {

    public static final String NEW = "NEW";
    public static final String CONTACTED = "CONTACTED";
    public static final String INTERVIEW = "INTERVIEW";
    public static final String HIRED = "HIRED";
    public static final String REJECTED = "REJECTED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long vacancyId;

    @Column(length = 80)
    private String vacancyTitle;

    @Column(length = 80)
    private String name;

    @Column(nullable = false, length = 40)
    private String phone;

    @Column(length = 1000)
    private String about;

    @Column(nullable = false, length = 16)
    private String status = NEW;

    /** Комментарий сотрудника. */
    @Column(columnDefinition = "TEXT")
    private String note;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
