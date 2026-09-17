package com.confectionery.crm.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Контрагент — поставщик сырья, привязывается к закупке (см. {@link Purchase}) жёсткой FK-
 * связью (одна закупка — один контрагент, несколько товаров, см. Purchase.items): нужно для
 * выгрузки закупки в 1С документом "Поступление товаров и услуг" (см. OneCPurchaseSyncService),
 * ссылающимся на Контрагента/Договор. Поэтому удалить контрагента, на которого уже есть
 * закупки, нельзя (см. CounterpartyService.delete) — в отличие от клиента, история закупок не
 * архивируется отдельно от контрагента, только целиком запрещаем удаление.
 *
 * <p>Список пополняется вручную или разовой выгрузкой всех контрагентов из 1С (см.
 * CounterpartyService.syncFromOneC) — 1С не различает поставщиков и покупателей в общем
 * справочнике "Контрагенты", поэтому туда попадут и контрагенты-клиенты тоже.
 */
@Entity
@Table(name = "counterparties")
@Getter
@Setter
@NoArgsConstructor
public class Counterparty {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    /** Ref_Key контрагента в 1С — задан, только если контрагент пришёл из синхронизации (см. CounterpartyService.syncFromOneC). */
    @Column(unique = true)
    private String oneCContragentKey;

    /** GUID основного договора контрагента в 1С (ОсновнойДоговорКонтрагента_Key) — подбирается автоматически вместе с oneCContragentKey. */
    private String oneCContractKey;

    /**
     * Причина, по которой последняя попытка синхронизации с 1С (создание нового контрагента —
     * см. CounterpartyService.create — или пометка на удаление — см. CounterpartyService.delete)
     * не удалась. Null, если проблем не было (или синхронизация ещё не запускалась).
     */
    @Column(length = 500)
    private String oneCSyncError;
}
