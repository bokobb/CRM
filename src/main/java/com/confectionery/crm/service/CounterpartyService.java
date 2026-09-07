package com.confectionery.crm.service;

import com.confectionery.crm.domain.Counterparty;
import com.confectionery.crm.repository.CounterpartyRepository;
import com.confectionery.crm.repository.PurchaseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Справочник контрагентов (в первую очередь поставщиков сырья, см. Purchase) — либо заводится
 * вручную, либо разово выгружается из 1С целиком (см. {@link #syncFromOneC}). См. класс-javadoc
 * {@link com.confectionery.crm.domain.Counterparty}.
 */
@Service
@RequiredArgsConstructor
public class CounterpartyService {

    private final CounterpartyRepository counterpartyRepository;
    private final PurchaseRepository purchaseRepository;
    private final OneCClient oneCClient;

    public List<Counterparty> findAll() {
        return counterpartyRepository.findAllByOrderByNameAsc();
    }

    public Counterparty findById(Long id) {
        return counterpartyRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Контрагент не найден: " + id));
    }

    @Transactional
    public Counterparty create(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Укажите название контрагента");
        }
        Counterparty counterparty = new Counterparty();
        counterparty.setName(name.trim());
        return counterpartyRepository.save(counterparty);
    }

    /** Purchase ссылается на контрагента жёсткой FK (см. Counterparty class-javadoc) — если по нему уже есть закупки, удалить нельзя. */
    @Transactional
    public void delete(Long id) {
        if (!counterpartyRepository.existsById(id)) {
            throw new IllegalArgumentException("Контрагент не найден: " + id);
        }
        if (purchaseRepository.existsByCounterpartyId(id)) {
            throw new IllegalStateException("Нельзя удалить — на этого контрагента есть закупки");
        }
        counterpartyRepository.deleteById(id);
    }

    /**
     * Загружает ВСЕХ контрагентов из справочника 1С (Catalog_Контрагенты) — тот же метод, что
     * использует {@link OneCClientLinkService} для привязки клиентов, поэтому в списке
     * окажутся и контрагенты-покупатели тоже (1С их не разделяет). Уже привязанные по
     * oneCContragentKey — обновляются по имени и договору (в 1С могли переименовать/сменить
     * основной договор), новые — создаются; контрагенты, заведённые в CRM вручную (без ключа
     * 1С), не трогаются и не дублируются, если их имя совпадает с новым из 1С — сопоставление
     * только по ключу, не по имени.
     */
    @Transactional
    public SyncResult syncFromOneC() {
        if (!oneCClient.isConfigured()) {
            throw new IllegalStateException("Подключение к 1С не настроено (crm.onec.base-url/username/password)");
        }
        List<OneCClient.Contragent> contragents = oneCClient.fetchContragents();
        int created = 0;
        int updated = 0;
        for (OneCClient.Contragent contragent : contragents) {
            if (contragent.ref().isBlank() || contragent.description().isBlank()) {
                continue;
            }
            Counterparty counterparty = counterpartyRepository.findByOneCContragentKey(contragent.ref())
                    .orElse(null);
            if (counterparty == null) {
                counterparty = new Counterparty();
                counterparty.setOneCContragentKey(contragent.ref());
                counterparty.setName(contragent.description().trim());
                counterparty.setOneCContractKey(contragent.contractKey());
                counterpartyRepository.save(counterparty);
                created++;
            } else if (!counterparty.getName().equals(contragent.description().trim())
                    || !contragent.contractKey().equals(counterparty.getOneCContractKey())) {
                counterparty.setName(contragent.description().trim());
                counterparty.setOneCContractKey(contragent.contractKey());
                counterpartyRepository.save(counterparty);
                updated++;
            }
        }
        return new SyncResult(created, updated, contragents.size());
    }

    public record SyncResult(int created, int updated, int totalInOneC) {
    }
}
