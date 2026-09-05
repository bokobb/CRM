package com.confectionery.crm.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "clients")
@Getter
@Setter
@NoArgsConstructor
public class Client {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Укажите имя клиента")
    @Column(nullable = false)
    private String name;

    private String phone;

    private String email;

    private String address;

    @Column(length = 2000)
    private String notes;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    /**
     * GUID контрагента в 1С (Catalog_Контрагенты.Ref_Key) — привязывается вручную/автопоиском
     * по имени (см. OneCClientLinkService). Null — клиент не привязан, заказы от него в 1С
     * не выгружаются (см. OneCOrderSyncService), админ получает уведомление при попытке.
     */
    private String oneCContragentKey;

    /** GUID основного договора контрагента в 1С (ОсновнойДоговорКонтрагента_Key) — подбирается автоматически вместе с oneCContragentKey. */
    private String oneCContractKey;

    /**
     * GUID именного "Типа цен номенклатуры" этого клиента в 1С (Catalog_ТипыЦенНоменклатуры) —
     * в этой базе у каждого клиента-дистрибьютора свой тип цен, названный его именем, и через
     * него в 1С ведётся его индивидуальный прайс (см. OneCClientPriceSyncService). Тем же
     * ключом заполняется ТипЦен_Key документа реализации при выгрузке заказа (см.
     * OneCOrderSyncService) — раньше там по ошибке стояла общая константа от одного клиента.
     */
    private String oneCPriceTypeKey;

    @OneToMany(mappedBy = "client", cascade = CascadeType.ALL, orphanRemoval = false)
    private List<Order> orders = new ArrayList<>();

    @OneToMany(mappedBy = "client", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ClientPrice> prices = new ArrayList<>();
}
