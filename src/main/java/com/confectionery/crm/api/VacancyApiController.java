package com.confectionery.crm.api;

import com.confectionery.crm.domain.Vacancy;
import com.confectionery.crm.domain.VacancyReply;
import com.confectionery.crm.service.VacancyReplyService;
import com.confectionery.crm.service.VacancyService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/** Меню «Вакансии» (вакансии для сайта /vacancies и отклики с него). В откликах персональные данные — только ADMIN/ACCOUNTANT. */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT')")
public class VacancyApiController {

    private final VacancyService vacancyService;
    private final VacancyReplyService replyService;

    public record VacancyDto(Long id, String title, String summary, String schedule, String experience, String salary,
                             String duties, String requirements, String conditions, boolean active, int sortOrder) {
        static VacancyDto from(Vacancy v) {
            return new VacancyDto(v.getId(), v.getTitle(), v.getSummary(), v.getSchedule(), v.getExperience(),
                    v.getSalary(), v.getDuties(), v.getRequirements(), v.getConditions(), v.isActive(), v.getSortOrder());
        }
    }

    public record ReplyDto(Long id, Long vacancyId, String vacancyTitle, String name, String phone, String about,
                           String status, String note, LocalDateTime createdAt) {
        static ReplyDto from(VacancyReply r) {
            return new ReplyDto(r.getId(), r.getVacancyId(), r.getVacancyTitle(), r.getName(), r.getPhone(),
                    r.getAbout(), r.getStatus(), r.getNote(), r.getCreatedAt());
        }
    }

    public record ActiveRequest(boolean active) {
    }

    public record OrderRequest(List<Long> ids) {
    }

    public record ReplyUpdate(String status, String note) {
    }

    @GetMapping("/vacancies")
    public List<VacancyDto> list() {
        return vacancyService.findAll().stream().map(VacancyDto::from).toList();
    }

    @PostMapping("/vacancies")
    public VacancyDto create(@RequestBody VacancyService.VacancyFields req) {
        return VacancyDto.from(vacancyService.save(null, req));
    }

    @PutMapping("/vacancies/{id}")
    public VacancyDto update(@PathVariable Long id, @RequestBody VacancyService.VacancyFields req) {
        return VacancyDto.from(vacancyService.save(id, req));
    }

    @PutMapping("/vacancies/{id}/active")
    public VacancyDto setActive(@PathVariable Long id, @RequestBody ActiveRequest req) {
        return VacancyDto.from(vacancyService.setActive(id, req.active()));
    }

    @PutMapping("/vacancies/order")
    public List<VacancyDto> reorder(@RequestBody OrderRequest req) {
        return vacancyService.reorder(req.ids()).stream().map(VacancyDto::from).toList();
    }

    @DeleteMapping("/vacancies/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        vacancyService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/vacancy-replies")
    public List<ReplyDto> replies() {
        return replyService.findAll().stream().map(ReplyDto::from).toList();
    }

    @PutMapping("/vacancy-replies/{id}")
    public ReplyDto updateReply(@PathVariable Long id, @RequestBody ReplyUpdate req) {
        return ReplyDto.from(replyService.update(id, req.status(), req.note()));
    }
}
