package com.confectionery.crm.service;

import com.confectionery.crm.domain.Client;
import com.confectionery.crm.domain.Product;
import com.confectionery.crm.repository.ClientRepository;
import com.confectionery.crm.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Индивидуальный прайс клиента в 1С ведётся не через договор (в этой базе у всех контрагентов
 * "Без договора", ТипЦен_Key пустой), а через отдельный именной "Тип цен номенклатуры" —
 * у каждого клиента-дистрибьютора свой, названный его именем (Catalog_ТипыЦенНоменклатуры).
 * Этот сервис подбирает клиенту такой тип цен (по имени, как и контрагента — см.
 * OneCClientLinkService) и подтягивает из регистра цен номенклатуры его актуальные цены в
 * {@link com.confectionery.crm.domain.ClientPrice} (см. ClientPriceService).
 */
@Service
@RequiredArgsConstructor
public class OneCClientPriceSyncService {

    private final OneCClient oneCClient;
    private final ClientRepository clientRepository;
    private final ProductRepository productRepository;
    private final ClientPriceService clientPriceService;

    /** Подбирает/возвращает GUID типа цен клиента — без сохранения, см. resolveAndSave. */
    String findPriceTypeKeyByName(String clientName, Map<String, String> priceTypeNames) {
        String needle = clientName.trim().toLowerCase();
        return priceTypeNames.entrySet().stream()
                .filter(e -> e.getValue().trim().toLowerCase().equals(needle))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    /** Подбирает и сохраняет тип цен клиента по имени, если ещё не привязан. Null, если не найден. */
    String resolvePriceTypeKey(Client client) {
        if (client.getOneCPriceTypeKey() != null) {
            return client.getOneCPriceTypeKey();
        }
        String key = findPriceTypeKeyByName(client.getName(), oneCClient.fetchPriceTypeNames());
        if (key == null) return null;
        client.setOneCPriceTypeKey(key);
        clientRepository.save(client);
        return key;
    }

    /** Обновляет индивидуальный прайс одного клиента из 1С. */
    @Transactional
    public ClientPriceSyncResult syncPrices(Long clientId) {
        if (!oneCClient.isConfigured()) {
            throw new IllegalStateException("Подключение к 1С не настроено (crm.onec.base-url/username/password)");
        }
        Client client = clientRepository.findById(clientId)
                .orElseThrow(() -> new IllegalArgumentException("Клиент не найден: " + clientId));
        return sync(client);
    }

    /** Обновляет прайс всех клиентов, у которых уже подобран (или подбирается по имени) тип цен в 1С. */
    @Transactional
    public BulkPriceSyncResult syncAll() {
        if (!oneCClient.isConfigured()) {
            throw new IllegalStateException("Подключение к 1С не настроено (crm.onec.base-url/username/password)");
        }
        int updatedClients = 0;
        int updatedPrices = 0;
        List<String> noMatch = new ArrayList<>();
        for (Client client : clientRepository.findAll()) {
            ClientPriceSyncResult r = sync(client);
            if (r.priceTypeFound()) {
                updatedClients++;
                updatedPrices += r.pricesUpdated();
            } else {
                noMatch.add(client.getName());
            }
        }
        return new BulkPriceSyncResult(updatedClients, updatedPrices, noMatch);
    }

    /** Ручная привязка типа цен по подстроке названия — для клиентов, чьё имя не совпадает с 1С дословно. */
    public Client linkPriceTypeByQuery(Long clientId, String query) {
        if (!oneCClient.isConfigured()) {
            throw new IllegalStateException("Подключение к 1С не настроено (crm.onec.base-url/username/password)");
        }
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Укажите название типа цен для поиска в 1С");
        }
        Client client = clientRepository.findById(clientId)
                .orElseThrow(() -> new IllegalArgumentException("Клиент не найден: " + clientId));

        String needle = query.trim().toLowerCase();
        Map<String, String> priceTypeNames = oneCClient.fetchPriceTypeNames();
        List<Map.Entry<String, String>> matches = priceTypeNames.entrySet().stream()
                .filter(e -> e.getValue().toLowerCase().contains(needle))
                .toList();

        if (matches.isEmpty()) {
            throw new IllegalArgumentException("В 1С не найден тип цен по запросу «" + query + "»");
        }
        if (matches.size() > 1) {
            String names = matches.stream().map(Map.Entry::getValue).limit(5).reduce((a, b) -> a + ", " + b).orElse("");
            throw new IllegalArgumentException("Найдено несколько типов цен, уточните запрос: " + names);
        }
        client.setOneCPriceTypeKey(matches.get(0).getKey());
        clientRepository.save(client);
        return sync(client).client();
    }

    private ClientPriceSyncResult sync(Client client) {
        String priceTypeKey = resolvePriceTypeKey(client);
        if (priceTypeKey == null) {
            return new ClientPriceSyncResult(client, false, 0, List.of());
        }

        Map<String, String> nomenclatureNames = oneCClient.fetchNomenclatureNames();
        List<Product> products = productRepository.findAll();

        // Регистр периодический — на каждую номенклатуру берём самую свежую активную запись.
        Map<String, OneCClient.PriceRow> latestByNomenclature = new HashMap<>();
        for (OneCClient.PriceRow row : oneCClient.fetchPricesByType(priceTypeKey)) {
            if (!row.active()) continue;
            OneCClient.PriceRow current = latestByNomenclature.get(row.nomenclatureKey());
            if (current == null || row.period().compareTo(current.period()) > 0) {
                latestByNomenclature.put(row.nomenclatureKey(), row);
            }
        }

        int updated = 0;
        List<String> unmatched = new ArrayList<>();
        for (OneCClient.PriceRow row : latestByNomenclature.values()) {
            String name = nomenclatureNames.get(row.nomenclatureKey());
            if (name == null || name.isBlank()) continue;
            Product product = ProductMatcher.match(name, products);
            if (product == null) {
                unmatched.add(name);
                continue;
            }
            clientPriceService.upsert(client.getId(), product.getId(), row.price());
            updated++;
        }
        return new ClientPriceSyncResult(client, true, updated, unmatched);
    }

    record ClientPriceSyncResult(Client client, boolean priceTypeFound, int pricesUpdated, List<String> unmatched) {
    }

    public record BulkPriceSyncResult(int clientsUpdated, int pricesUpdated, List<String> clientsWithoutPriceType) {
    }
}
