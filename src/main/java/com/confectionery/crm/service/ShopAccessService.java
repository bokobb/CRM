package com.confectionery.crm.service;

import com.confectionery.crm.domain.Client;
import com.confectionery.crm.domain.Role;
import com.confectionery.crm.domain.CallbackRequest;
import com.confectionery.crm.domain.ShopAccessRequest;
import com.confectionery.crm.repository.CallbackRequestRepository;
import com.confectionery.crm.repository.ShopAccessRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * Допуск новых номеров на сайт клиентов — см. {@link ShopAccessRequest}. Номера сравниваются
 * по последним 10 цифрам, как и в {@link ClientService#findByPhone}.
 */
@Service
@RequiredArgsConstructor
public class ShopAccessService {

    /** Сколько новых заявок в час ещё шлют push админу — дальше пишутся молча (защита от спама номерами). */
    private static final int PUSH_LIMIT_PER_HOUR = 10;

    private final ShopAccessRequestRepository repository;
    private final ClientService clientService;
    private final NotificationService notificationService;
    private final CallbackRequestRepository callbackRepository;

    /** Последняя ожидающая или отклонённая заявка с этого номера (разрешённая уже стала клиентом). */
    public Optional<ShopAccessRequest> findOpen(String phone) {
        String needle = ClientService.phoneSuffix(phone);
        if (needle.isEmpty()) return Optional.empty();
        return repository.findByStatusInOrderByCreatedAtDesc(
                        List.of(ShopAccessRequest.PENDING, ShopAccessRequest.REJECTED)).stream()
                .filter(r -> needle.equals(ClientService.phoneSuffix(r.getPhone())))
                .findFirst();
    }

    /** Новая заявка + push админу; повторный вход с тем же номером новую заявку не создаёт. */
    @Transactional
    public ShopAccessRequest request(String phone, String name) {
        Optional<ShopAccessRequest> open = findOpen(phone);
        if (open.isPresent()) {
            return open.get();
        }
        boolean push = repository.countByCreatedAtAfter(LocalDateTime.now().minusHours(1)) < PUSH_LIMIT_PER_HOUR;
        ShopAccessRequest r = new ShopAccessRequest();
        r.setPhone(phone.trim());
        r.setName(name.trim());
        r = repository.save(r);
        if (push) {
            notificationService.notifyRole(Role.ADMIN,
                    "Новый клиент просит доступ к сайту: " + r.getName() + ", " + r.getPhone()
                            + ". Разрешить или отклонить — в приложении, «Клиенты».", null);
        }
        return r;
    }

    /** Заявки «Перезвоните мне» с рекламной страницы: сколько в час ещё доходит до админа. */
    private static final int CALLBACK_LIMIT_PER_HOUR = 20;
    private final Deque<LocalDateTime> recentCallbacks = new ArrayDeque<>();

    /**
     * Форма «Перезвоните мне» на сайте — не заявка на доступ к сайту, а просьба позвонить:
     * сохраняется в CallbackRequest (отрабатывают в приложении) + push админу и продажам.
     * Сверх лимита в час запросы молча отбрасываются (false) — защита от спама с открытой формы.
     */
    public synchronized boolean requestCallback(String phone, String name, String business) {
        LocalDateTime hourAgo = LocalDateTime.now().minusHours(1);
        while (!recentCallbacks.isEmpty() && recentCallbacks.peekFirst().isBefore(hourAgo)) {
            recentCallbacks.pollFirst();
        }
        if (recentCallbacks.size() >= CALLBACK_LIMIT_PER_HOUR) {
            return false;
        }
        recentCallbacks.addLast(LocalDateTime.now());
        CallbackRequest r = new CallbackRequest();
        r.setPhone(phone.trim());
        r.setName(name == null || name.isBlank() ? null : name.trim());
        r.setBusiness(business == null || business.isBlank() ? null : business.trim());
        callbackRepository.save(r);
        String who = r.getName() == null ? "без имени" : r.getName();
        String kind = r.getBusiness() == null ? "" : " (" + r.getBusiness() + ")";
        String text = "Перезвонить с сайта: " + who + kind + ", " + r.getPhone() + ". Отработать — «Отклики и заявки».";
        notificationService.notifyRole(Role.ADMIN, text, null);
        notificationService.notifyRole(Role.SALES, text, null);
        return true;
    }

    /** «Отклики и заявки» › «Заявки»: новые сверху. */
    public List<CallbackRequest> callbacks() {
        return callbackRepository.findAllByOrderByCreatedAtDesc();
    }

    @Transactional
    public CallbackRequest updateCallback(Long id, String status, String note, String who) {
        CallbackRequest r = callbackRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Заявка не найдена"));
        if (status != null && !status.equals(r.getStatus())) {
            if (!java.util.Set.of(CallbackRequest.NEW, CallbackRequest.CONTACTED, CallbackRequest.ORDERED,
                    CallbackRequest.REJECTED).contains(status)) {
                throw new IllegalStateException("Неизвестный статус: " + status);
            }
            r.setStatus(status);
            r.setHandledAt(LocalDateTime.now());
            r.setHandledBy(who);
        }
        if (note != null) r.setNote(note.isBlank() ? null : note.strip());
        return r;
    }

    public List<ShopAccessRequest> pending() {
        return repository.findByStatusOrderByCreatedAtDesc(ShopAccessRequest.PENDING);
    }

    /** Разрешить: клиент с этим номером уже есть (завели вручную) — привязываемся к нему, иначе создаём. */
    @Transactional
    public ShopAccessRequest approve(Long id, String admin) {
        ShopAccessRequest r = get(id);
        Client client = clientService.findByPhone(r.getPhone()).orElseGet(() -> {
            Client c = new Client();
            c.setPhone(r.getPhone());
            c.setName(r.getName());
            return clientService.save(c);
        });
        r.setClientId(client.getId());
        return decide(r, ShopAccessRequest.APPROVED, admin);
    }

    @Transactional
    public ShopAccessRequest reject(Long id, String admin) {
        return decide(get(id), ShopAccessRequest.REJECTED, admin);
    }

    private ShopAccessRequest decide(ShopAccessRequest r, String status, String admin) {
        r.setStatus(status);
        r.setDecidedAt(LocalDateTime.now());
        r.setDecidedBy(admin);
        return repository.save(r);
    }

    private ShopAccessRequest get(Long id) {
        ShopAccessRequest r = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Заявка не найдена: " + id));
        if (!ShopAccessRequest.PENDING.equals(r.getStatus())) {
            throw new IllegalArgumentException("По заявке уже принято решение");
        }
        return r;
    }
}
