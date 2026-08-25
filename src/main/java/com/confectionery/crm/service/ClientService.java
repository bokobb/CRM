package com.confectionery.crm.service;

import com.confectionery.crm.domain.Client;
import com.confectionery.crm.repository.ClientRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ClientService {

    private final ClientRepository clientRepository;

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

    public void delete(Long id) {
        clientRepository.deleteById(id);
    }
}
