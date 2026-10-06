package com.confectionery.crm.service;

import com.confectionery.crm.domain.Client;
import com.confectionery.crm.domain.Role;
import com.confectionery.crm.domain.ShopAccessRequest;
import com.confectionery.crm.repository.ShopAccessRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
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
