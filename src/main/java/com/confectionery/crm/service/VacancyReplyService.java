package com.confectionery.crm.service;

import com.confectionery.crm.domain.Role;
import com.confectionery.crm.domain.Vacancy;
import com.confectionery.crm.domain.VacancyReply;
import com.confectionery.crm.repository.VacancyReplyRepository;
import com.confectionery.crm.repository.VacancyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Отклики с сайта (/vacancies). Видят ADMIN и ACCOUNTANT (меню «Вакансии» › «Отклики»), им же push о новом.
 * Защита от спама: скрытое поле-ловушка website и не больше 5 откликов с одного адреса за 10 минут
 * (в памяти, как лимит формы «Перезвоните мне»). Хранятся полгода.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VacancyReplyService {

    static final Set<String> STATUSES = Set.of(VacancyReply.NEW, VacancyReply.CONTACTED, VacancyReply.INTERVIEW,
            VacancyReply.HIRED, VacancyReply.REJECTED);
    private static final int LIMIT_PER_ADDRESS = 5;
    private static final int LIMIT_MINUTES = 10;
    private static final int KEEP_MONTHS = 6;

    private final VacancyReplyRepository replyRepository;
    private final VacancyRepository vacancyRepository;
    private final NotificationService notificationService;
    private final Map<String, Deque<LocalDateTime>> recentByAddress = new HashMap<>();

    /** Ошибка, которую можно показать человеку на сайте как есть. */
    public static class ReplyRejected extends RuntimeException {
        public ReplyRejected(String message) {
            super(message);
        }
    }

    /**
     * Принять отклик. Заполненная ловушка website — молча «принято», без сохранения.
     * @throws ReplyRejected с понятным текстом по-русски
     */
    @Transactional
    public void submit(Long vacancyId, String name, String phone, String about, String website, String address) {
        if (website != null && !website.isBlank()) {
            log.info("Отклик с сайта отброшен ловушкой для ботов, адрес {}", address);
            return;
        }
        String digits = phone == null ? "" : phone.replaceAll("\\D", "");
        if (digits.length() < 10 || digits.length() > 15 || phone.length() > 40) {
            throw new ReplyRejected("Укажите номер телефона полностью, чтобы мы могли перезвонить.");
        }
        String cleanName = name == null || name.isBlank() ? null : name.strip();
        if (cleanName != null && cleanName.length() > 80) {
            throw new ReplyRejected("Имя — не длиннее 80 символов.");
        }
        String cleanAbout = about == null || about.isBlank() ? null : about.strip();
        if (cleanAbout != null && cleanAbout.length() > 1000) {
            throw new ReplyRejected("«Коротко о себе» — не длиннее 1000 символов.");
        }
        Vacancy vacancy = null;
        if (vacancyId != null) {
            vacancy = vacancyRepository.findById(vacancyId).filter(Vacancy::isActive)
                    .orElseThrow(() -> new ReplyRejected("Эта вакансия уже закрыта. Обновите страницу и выберите другую."));
        }
        if (!allow(address)) {
            throw new ReplyRejected("Слишком много откликов подряд. Попробуйте через 10 минут или позвоните нам.");
        }
        VacancyReply r = new VacancyReply();
        r.setVacancyId(vacancyId);
        r.setVacancyTitle(vacancy == null ? null : vacancy.getTitle());
        r.setName(cleanName);
        r.setPhone(phone.strip());
        r.setAbout(cleanAbout);
        replyRepository.save(r);

        String text = "Отклик с сайта: " + (vacancy == null ? "в резерв" : vacancy.getTitle()) + ", "
                + (cleanName == null ? "без имени" : cleanName) + ", " + r.getPhone();
        notificationService.notifyRole(Role.ADMIN, text, null);
        notificationService.notifyRole(Role.ACCOUNTANT, text, null);
    }

    private synchronized boolean allow(String address) {
        String key = address == null ? "?" : address;
        LocalDateTime since = LocalDateTime.now().minusMinutes(LIMIT_MINUTES);
        recentByAddress.values().forEach(q -> { while (!q.isEmpty() && q.peekFirst().isBefore(since)) q.pollFirst(); });
        recentByAddress.values().removeIf(Deque::isEmpty);
        Deque<LocalDateTime> q = recentByAddress.computeIfAbsent(key, k -> new ArrayDeque<>());
        if (q.size() >= LIMIT_PER_ADDRESS) return false;
        q.addLast(LocalDateTime.now());
        return true;
    }

    public List<VacancyReply> findAll() {
        return replyRepository.findAllByOrderByCreatedAtDesc();
    }

    @Transactional
    public VacancyReply update(Long id, String status, String note) {
        VacancyReply r = replyRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Отклик не найден"));
        if (status != null) {
            if (!STATUSES.contains(status)) throw new IllegalStateException("Неизвестный статус: " + status);
            r.setStatus(status);
        }
        if (note != null) r.setNote(note.isBlank() ? null : note.strip());
        return r;
    }

    /** Каждую ночь удаляет отклики старше полугода — в них имена и телефоны. */
    @Scheduled(cron = "0 30 3 * * *", zone = "${crm.schedule-zone:GMT+05:00}")
    @Transactional
    public void purgeOld() {
        long removed = replyRepository.deleteByCreatedAtBefore(LocalDateTime.now().minusMonths(KEEP_MONTHS));
        if (removed > 0) log.info("Удалено откликов на вакансии старше {} мес.: {}", KEEP_MONTHS, removed);
    }
}
