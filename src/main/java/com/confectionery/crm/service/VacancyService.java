package com.confectionery.crm.service;

import com.confectionery.crm.domain.Vacancy;
import com.confectionery.crm.repository.VacancyReplyRepository;
import com.confectionery.crm.repository.VacancyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** Вакансии для страницы сайта /vacancies (меню «Вакансии» в приложении). */
@Service
@RequiredArgsConstructor
public class VacancyService {

    private final VacancyRepository vacancyRepository;
    private final VacancyReplyRepository replyRepository;

    public record VacancyFields(String title, String summary, String schedule, String experience, String salary,
                                String duties, String requirements, String conditions, Boolean active) {
    }

    public List<Vacancy> findAll() {
        return vacancyRepository.findAllByOrderBySortOrderAscIdAsc();
    }

    public List<Vacancy> findActive() {
        return vacancyRepository.findByActiveTrueOrderBySortOrderAscIdAsc();
    }

    public Vacancy get(Long id) {
        return vacancyRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Вакансия не найдена"));
    }

    @Transactional
    public Vacancy save(Long id, VacancyFields f) {
        Vacancy v;
        if (id == null) {
            v = new Vacancy();
            v.setSortOrder(findAll().stream().mapToInt(Vacancy::getSortOrder).max().orElse(0) + 1);
        } else {
            v = get(id);
        }
        v.setTitle(required(f.title(), 80, "Название"));
        v.setSummary(optional(f.summary(), 140, "Строка под названием"));
        v.setSchedule(required(f.schedule(), 60, "График"));
        v.setExperience(required(f.experience(), 60, "Опыт"));
        v.setSalary(optional(f.salary(), 60, "Зарплата"));
        v.setDuties(optional(f.duties(), 4000, "«Что делать»"));
        v.setRequirements(optional(f.requirements(), 4000, "«Кого ищем»"));
        v.setConditions(optional(f.conditions(), 4000, "«Что предлагаем»"));
        if (f.active() != null) v.setActive(f.active());
        v.setUpdatedAt(LocalDateTime.now());
        return vacancyRepository.save(v);
    }

    @Transactional
    public Vacancy setActive(Long id, boolean active) {
        Vacancy v = get(id);
        v.setActive(active);
        v.setUpdatedAt(LocalDateTime.now());
        return v;
    }

    /** Порядок на сайте — как в списке ids (перетаскивание в приложении). */
    @Transactional
    public List<Vacancy> reorder(List<Long> ids) {
        for (int i = 0; i < ids.size(); i++) {
            get(ids.get(i)).setSortOrder(i + 1);
        }
        return findAll();
    }

    /** С откликами не удаляем — только скрыть, иначе отклики потеряют связь с вакансией. */
    @Transactional
    public void delete(Long id) {
        if (replyRepository.existsByVacancyId(id)) {
            throw new IllegalStateException("На вакансию есть отклики — её можно только скрыть с сайта");
        }
        vacancyRepository.delete(get(id));
    }

    private static String required(String value, int max, String field) {
        String v = optional(value, max, field);
        if (v == null) throw new IllegalStateException("Заполните поле " + field);
        return v;
    }

    private static String optional(String value, int max, String field) {
        if (value == null || value.isBlank()) return null;
        String v = value.strip();
        if (v.length() > max) throw new IllegalStateException(field + " — не длиннее " + max + " символов");
        return v;
    }
}
