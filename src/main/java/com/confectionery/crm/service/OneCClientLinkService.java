package com.confectionery.crm.service;

import com.confectionery.crm.domain.Client;
import com.confectionery.crm.repository.ClientRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Привязка клиентов CRM к контрагентам 1С (Client.oneCContragentKey/oneCContractKey) —
 * без неё заказ этого клиента не выгружается в 1С (см. OneCOrderSyncService). Новых
 * контрагентов в 1С не создаём — только ищем среди уже существующих, привязку нового клиента
 * без совпадения подтверждает админ вручную (см. ClientController).
 */
@Service
@RequiredArgsConstructor
public class OneCClientLinkService {

    private final OneCClient oneCClient;
    private final ClientRepository clientRepository;

    /** Точное совпадение по названию среди ещё не привязанных клиентов. */
    public AutoLinkResult autoLinkAll() {
        if (!oneCClient.isConfigured()) {
            throw new IllegalStateException("Подключение к 1С не настроено (crm.onec.base-url/username/password)");
        }
        List<OneCClient.Contragent> contragents = oneCClient.fetchContragents();
        List<Client> unlinked = clientRepository.findAll().stream()
                .filter(c -> c.getOneCContragentKey() == null)
                .toList();

        int linked = 0;
        List<String> stillUnmatched = new ArrayList<>();
        for (Client client : unlinked) {
            OneCClient.Contragent match = findByName(contragents, client.getName());
            if (match == null) {
                stillUnmatched.add(client.getName());
                continue;
            }
            applyLink(client, match);
            linked++;
        }
        return new AutoLinkResult(linked, stillUnmatched);
    }

    /**
     * Ручная привязка одного клиента по подстроке названия в 1С — используется, когда имя в
     * CRM не совпадает с 1С дословно. Требует однозначного результата поиска.
     */
    public Client linkByQuery(Long clientId, String query) {
        if (!oneCClient.isConfigured()) {
            throw new IllegalStateException("Подключение к 1С не настроено (crm.onec.base-url/username/password)");
        }
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Укажите название контрагента для поиска в 1С");
        }
        Client client = clientRepository.findById(clientId)
                .orElseThrow(() -> new IllegalArgumentException("Клиент не найден: " + clientId));

        String needle = query.trim().toLowerCase();
        List<OneCClient.Contragent> matches = oneCClient.fetchContragents().stream()
                .filter(c -> c.description().toLowerCase().contains(needle))
                .toList();

        if (matches.isEmpty()) {
            throw new IllegalArgumentException("В 1С не найден контрагент по запросу «" + query + "»");
        }
        if (matches.size() > 1) {
            String names = matches.stream().map(OneCClient.Contragent::description).limit(5)
                    .reduce((a, b) -> a + ", " + b).orElse("");
            throw new IllegalArgumentException("Найдено несколько контрагентов, уточните запрос: " + names);
        }
        applyLink(client, matches.get(0));
        return client;
    }

    public void unlink(Long clientId) {
        Client client = clientRepository.findById(clientId)
                .orElseThrow(() -> new IllegalArgumentException("Клиент не найден: " + clientId));
        client.setOneCContragentKey(null);
        client.setOneCContractKey(null);
        clientRepository.save(client);
    }

    private void applyLink(Client client, OneCClient.Contragent contragent) {
        client.setOneCContragentKey(contragent.ref());
        client.setOneCContractKey(contragent.contractKey());
        clientRepository.save(client);
    }

    private static OneCClient.Contragent findByName(List<OneCClient.Contragent> contragents, String name) {
        String needle = name.trim().toLowerCase();
        return contragents.stream()
                .filter(c -> c.description().trim().toLowerCase().equals(needle))
                .findFirst()
                .orElse(null);
    }

    public record AutoLinkResult(int linked, List<String> unmatched) {
    }
}
