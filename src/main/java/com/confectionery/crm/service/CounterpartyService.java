package com.confectionery.crm.service;

import com.confectionery.crm.domain.Counterparty;
import com.confectionery.crm.domain.Role;
import com.confectionery.crm.repository.CounterpartyRepository;
import com.confectionery.crm.repository.PurchaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Справочник контрагентов (в первую очередь поставщиков сырья, см. Purchase) — либо заводится
 * вручную (и сразу же выгружается в 1С, см. {@link #create}), либо разово выгружается из 1С
 * целиком (см. {@link #syncFromOneC}). См. класс-javadoc {@link com.confectionery.crm.domain.Counterparty}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CounterpartyService {

    private final CounterpartyRepository counterpartyRepository;
    private final PurchaseRepository purchaseRepository;
    private final OneCClient oneCClient;
    private final NotificationService notificationService;

    public List<Counterparty> findAll() {
        return counterpartyRepository.findAllByOrderByNameAsc();
    }

    public Counterparty findById(Long id) {
        return counterpartyRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Контрагент не найден: " + id));
    }

    /**
     * Создаёт контрагента локально и сразу же пытается создать соответствующий элемент в
     * справочнике 1С "Контрагенты" (см. {@link #tryCreateInOneC}) — чтобы закупки на него сразу
     * могли выгружаться в 1С (см. OneCPurchaseSyncService.sync, требует oneCContragentKey), а не
     * молча зависали с ошибкой "контрагент не привязан к 1С" до следующей ручной загрузки.
     */
    @Transactional
    public Counterparty create(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Укажите название контрагента");
        }
        Counterparty counterparty = new Counterparty();
        counterparty.setName(name.trim());
        counterparty = counterpartyRepository.save(counterparty);
        tryCreateInOneC(counterparty);
        return counterparty;
    }

    /**
     * Best-effort, как и остальная интеграция с 1С (см. OneCOrderSyncService.trySyncOrder):
     * контрагент в CRM уже сохранён и не откатывается, если 1С недоступна или создание не
     * удалось — причина пишется в {@link Counterparty#getOneCSyncError()} и уходит уведомлением
     * админу. Повторно синхронизировать такого контрагента вручную нельзя (нет кнопки ресинка,
     * не запрошено) — единственный путь восстановления: удалить и создать заново.
     */
    private void tryCreateInOneC(Counterparty counterparty) {
        if (!oneCClient.isConfigured()) {
            return;
        }
        try {
            String ref = oneCClient.createContragent(counterparty.getName());
            counterparty.setOneCContragentKey(ref);
            counterparty.setOneCSyncError(null);
            counterpartyRepository.save(counterparty);
        } catch (Exception e) {
            log.warn("Не удалось создать контрагента «{}» в 1С: {}", counterparty.getName(), e.getMessage());
            counterparty.setOneCSyncError(e.getMessage());
            counterpartyRepository.save(counterparty);
            notificationService.notifyRole(Role.ADMIN,
                    "Контрагент «" + counterparty.getName() + "» не выгружен в 1С: " + e.getMessage(), null);
        }
    }

    /**
     * Purchase ссылается на контрагента жёсткой FK (см. Counterparty class-javadoc) — если по
     * нему уже есть закупки, удалить нельзя (в CRM удаление вообще не происходит, значит и в 1С
     * ничего помечать не нужно). Если закупок нет — контрагент сначала помечается на удаление в
     * 1С (см. {@link #tryMarkForDeletion}, best-effort), затем удаляется локально.
     */
    @Transactional
    public void delete(Long id) {
        Counterparty counterparty = findById(id);
        if (purchaseRepository.existsByCounterpartyId(id)) {
            throw new IllegalStateException("Нельзя удалить — на этого контрагента есть закупки");
        }
        tryMarkForDeletion(counterparty);
        counterpartyRepository.deleteById(id);
    }

    /**
     * Ставит флаг "Пометка удаления" на элементе справочника 1С (не физическое удаление — см.
     * OneCClient.markForDeletion, тот же приём, что и у ProductionRequest/Order). Best-effort:
     * недоступность 1С не блокирует удаление в CRM — откатывать всё равно нечего (закупок на
     * контрагента нет, иначе delete() выше уже бросил бы исключение). Ничего не делает, если
     * контрагент никогда не был связан с 1С (заведён вручную, и создание в 1С не удалось/не
     * запускалось — см. {@link #tryCreateInOneC}).
     */
    private void tryMarkForDeletion(Counterparty counterparty) {
        if (!oneCClient.isConfigured() || counterparty.getOneCContragentKey() == null) {
            return;
        }
        try {
            oneCClient.markForDeletion("Catalog_Контрагенты", counterparty.getOneCContragentKey(), true);
        } catch (Exception e) {
            log.warn("Не удалось пометить контрагента «{}» на удаление в 1С: {}", counterparty.getName(), e.getMessage());
            notificationService.notifyRole(Role.ADMIN,
                    "Контрагент «" + counterparty.getName() + "» удалён в CRM, но не помечен на удаление в 1С: "
                            + e.getMessage(), null);
        }
    }

    /**
     * Загружает ВСЕХ контрагентов из справочника 1С (Catalog_Контрагенты) — тот же метод, что
     * использует {@link OneCClientLinkService} для привязки клиентов, поэтому в списке
     * окажутся и контрагенты-покупатели тоже (1С их не разделяет). Уже привязанные по
     * oneCContragentKey — обновляются по имени и договору (в 1С могли переименовать/сменить
     * основной договор), новые — создаются; контрагенты, заведённые в CRM вручную (без ключа
     * 1С), не трогаются и не дублируются, если их имя совпадает с новым из 1С — сопоставление
     * только по ключу, не по имени.
     *
     * <p>Заодно чистит "осиротевших" — контрагентов CRM, у которых {@code oneCContragentKey}
     * задан (то есть когда-то пришли из 1С или были туда выгружены), но в текущем списке 1С их
     * уже нет (удалили/переобъединили в 1С). Вручную заведённые (ключ ещё не пришёл или так и не
     * настроена интеграция) не трогаются — только реальные "хвосты" от 1С. Тот же самый запрет,
     * что и у ручного удаления (см. {@link #delete}) — если на контрагента уже есть закупки,
     * не удаляем, а копим в {@code SyncResult.skippedHasPurchases} для ручной проверки: закупки
     * важнее чистоты справочника, разбираться в этом случае должен человек.
     */
    @Transactional
    public SyncResult syncFromOneC() {
        if (!oneCClient.isConfigured()) {
            throw new IllegalStateException("Подключение к 1С не настроено (crm.onec.base-url/username/password)");
        }
        List<OneCClient.Contragent> contragents = oneCClient.fetchContragents();
        Set<String> refsInOneC = contragents.stream()
                .map(OneCClient.Contragent::ref)
                .filter(ref -> !ref.isBlank())
                .collect(Collectors.toSet());
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

        int deleted = 0;
        List<String> skippedHasPurchases = new ArrayList<>();
        for (Counterparty counterparty : counterpartyRepository.findAll()) {
            if (counterparty.getOneCContragentKey() == null || refsInOneC.contains(counterparty.getOneCContragentKey())) {
                continue;
            }
            if (purchaseRepository.existsByCounterpartyId(counterparty.getId())) {
                skippedHasPurchases.add(counterparty.getName());
                continue;
            }
            counterpartyRepository.delete(counterparty);
            deleted++;
        }

        return new SyncResult(created, updated, deleted, contragents.size(), skippedHasPurchases);
    }

    public record SyncResult(int created, int updated, int deleted, int totalInOneC, List<String> skippedHasPurchases) {
    }
}
