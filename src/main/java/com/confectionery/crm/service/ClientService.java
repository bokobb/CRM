package com.confectionery.crm.service;

import com.confectionery.crm.domain.Client;
import com.confectionery.crm.repository.ClientPriceRepository;
import com.confectionery.crm.repository.ClientRepository;
import com.confectionery.crm.repository.NotificationRepository;
import com.confectionery.crm.repository.OrderRepository;
import com.confectionery.crm.repository.ProductReturnRepository;
import com.confectionery.crm.repository.WhatsAppInboundMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ClientService {

    private final ClientRepository clientRepository;
    private final OrderRepository orderRepository;
    private final ClientPriceRepository clientPriceRepository;
    private final WhatsAppInboundMessageRepository whatsAppInboundMessageRepository;
    private final NotificationRepository notificationRepository;
    private final ProductReturnRepository productReturnRepository;

    public List<Client> findAll() {
        return clientRepository.findAll();
    }

    /**
     * Ищет клиента по номеру телефона, сравнивая только последние 10 цифр — формат
     * телефона в базе не унифицирован ("+7 900 111-22-33" и т.п.), а номер из WhatsApp
     * приходит в другом виде ("77024780909"). Используется приёмом заказов из WhatsApp
     * (см. WhatsAppOrderIntakeService).
     */
    public Optional<Client> findByPhone(String rawPhone) {
        String needle = phoneSuffix(rawPhone);
        if (needle.isEmpty()) return Optional.empty();
        return clientRepository.findAll().stream()
                .filter(c -> needle.equals(phoneSuffix(c.getPhone())))
                .findFirst();
    }

    private static String phoneSuffix(String phone) {
        if (phone == null) return "";
        String digits = phone.replaceAll("\\D", "");
        return digits.length() >= 10 ? digits.substring(digits.length() - 10) : digits;
    }

    public Client findById(Long id) {
        return clientRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Клиент не найден: " + id));
    }

    public Client save(Client client) {
        return clientRepository.save(client);
    }

    /**
     * Полное каскадное удаление клиента вместе со всей ЕГО историей: заказы (позиции и
     * производственные задания по ним каскадятся автоматически — см. Order.items/
     * productionTasks), возвраты, уведомления по этим заказам, сообщения из WhatsApp и индивидуальные
     * цены. В отличие от товара — тут нет риска задеть чужие данные, вся эта история и так
     * принадлежит только этому клиенту, поэтому ограничений на архивирование не делаем.
     */
    @Transactional
    public void delete(Long id) {
        Client client = findById(id);
        // Возвраты ссылаются и на клиента, и на его заказы — удаляем их до заказов.
        productReturnRepository.deleteAll(productReturnRepository.findByClientIdOrderByCreatedAtDesc(id));
        for (var order : orderRepository.findByClientIdOrderByCreatedAtDesc(id)) {
            notificationRepository.deleteAll(notificationRepository.findByOrderId(order.getId()));
            orderRepository.delete(order);
        }
        whatsAppInboundMessageRepository.deleteAll(whatsAppInboundMessageRepository.findByClientId(id));
        clientPriceRepository.deleteAll(clientPriceRepository.findByClientId(id));
        clientRepository.delete(client);
    }
}
