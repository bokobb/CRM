package com.confectionery.crm.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.BatchSize;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "products")
@Getter
@Setter
@NoArgsConstructor
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Укажите название товара")
    @Column(nullable = false)
    private String name;

    private String category;

    /**
     * Группа товара для рецептов и заявок на сырьё (например, "Коржик" объединяет Коржики/
     * Коржик Сдоба/Коржик Сердечко). Отдельно от category — та используется для каталога
     * заказов, эта — для разбиения заявок (см. ProductionRequestService.createGrouped).
     * Колонка называется product_group, не group — GROUP зарезервировано в SQL.
     */
    @Column(name = "product_group")
    private String group;

    @Column(length = 1000)
    private String description;

    /** Единица измерения (шт, кг и т.д.) — для складского учёта и накладной. */
    private String unit;

    /**
     * Вес одной штуки, г — только для штучных товаров, где норму при оприходовании нужно
     * сверять по общей массе, а не по штукам (см. ProductionRequestService.massSummary):
     * несколько товаров одной группы (например, Чак-чак Б/мини/резанный/резанный мини) режутся
     * из одного замеса на разные фасовки, и рабочие могут случайно перепутать пропорции между
     * вариантами при нарезке — построчно штуки не сойдутся с нормой, хотя по массе сырья всё в
     * порядке. Null (по умолчанию) — товар в такой сверке не участвует, используется обычная
     * построчная проверка (см. ProductionRequestLine.isInsufficient).
     */
    @Column(precision = 12, scale = 3)
    private BigDecimal netWeightGrams;

    /**
     * Эталон для пересчёта отклонения массы в штуки в сверке заявки на сырьё (например, «Чак Чак
     * Б» — самый крупный вариант группы, см. ProductionRequestService.massSummary): «не хватает
     * N кг» переводится в «≈ M шт. <это товар>», даже если сам он не входит в конкретную заявку.
     * Должен быть отмечен максимум у одного товара в группе (product_group) и иметь заданный
     * {@link #netWeightGrams} — иначе не учитывается. Если ни один товар группы так не отмечен,
     * используется самый тяжёлый вариант, который реально есть в заявке (запасной вариант).
     * Boolean (не примитив) и без {@code nullable=false} — колонка добавляется поверх уже
     * существующей непустой таблицы products, ddl-auto=update не умеет NOT NULL с DEFAULT на
     * ALTER TABLE (см. active/semiFinished выше); null трактуется как false в коде.
     */
    private Boolean massReferenceProduct = false;

    /**
     * Упаковка для отгрузки — по ним считается «Объём и масса» заказа (свайп заказа вправо в
     * приложении): коробок = ⌈кол-во / в коробке⌉, объём = коробок × объём коробки, масса =
     * кол-во × вес единицы. Штуки в коробке, если пусто, берутся из названия (приложение,
     * Product.packingUnits). Бланк собирает само приложение (mobile/lib/services/packing_pdf.dart). Вес — брутто одной единицы товара (шт или кг), кг; это не
     * {@link #netWeightGrams} (тот только для сверки массы в производстве). Все три необязательны.
     */
    @Column(precision = 12, scale = 3)
    private BigDecimal unitWeightKg;

    /** Сколько единиц товара (шт/кг) уходит в одну коробку. */
    @Column(precision = 12, scale = 3)
    private BigDecimal unitsPerBox;

    /** Объём одной коробки, м³. */
    @Column(precision = 12, scale = 5)
    private BigDecimal boxVolumeM3;

    /**
     * Вторая упаковка (необязательно) — когда клиент выбирает, например, по 12 или по 24 в коробке
     * («Коржики (12/24 шт)»): своё количество и свой объём коробки — выбирается при отгрузке.
     */
    @Column(precision = 12, scale = 3)
    private BigDecimal unitsPerBox2;

    @Column(precision = 12, scale = 5)
    private BigDecimal boxVolume2M3;

    public boolean isMassReferenceProduct() {
        return Boolean.TRUE.equals(massReferenceProduct);
    }

    /** Ссылка на фото товара (внешний URL) — показывается в мобильном приложении при оформлении заказа. */
    @Column(length = 2000)
    private String imageUrl;

    @NotNull
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price = BigDecimal.ZERO;

    /**
     * Остаток готовой продукции на складе ГП. Не помечен nullable=false в БД специально:
     * колонка добавлена поверх уже существующей таблицы с данными (ddl-auto=update не умеет
     * задавать DEFAULT при ALTER TABLE ... NOT NULL на непустой таблице) — обязательность
     * гарантируется на уровне Java (значение по умолчанию ниже) и явным backfill существующих строк.
     */
    @NotNull
    @Column(precision = 12, scale = 3)
    private BigDecimal quantityInStock = BigDecimal.valueOf(1000);

    @OneToOne(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private Recipe recipe;

    /** Альбом товара, первое — обложка (= imageUrl). Правится только через ProductPhotoService. */
    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC, id ASC")
    @BatchSize(size = 100)
    private List<ProductPhoto> photos = new ArrayList<>();

    /**
     * Архивный товар (false) скрыт из каталога/списков для новых заказов, но остаётся в БД —
     * так удаление товара, который уже встречался в чьих-то заказах, не портит задним числом
     * суммы старых заказов (см. ProductService.delete). Колонка добавляется вручную через
     * ALTER TABLE ... DEFAULT true перед первым запуском с этим полем — тот же приём, что и у
     * quantityInStock выше, ddl-auto=update не умеет NOT NULL без DEFAULT на непустой таблице.
     */
    @Column(nullable = false)
    private boolean active = true;

    /**
     * Скрыт от клиентов (решение ADMIN, например "крекер 2 месяца не производим") — в отличие от
     * архива ({@link #active}) товар остаётся в CRM для сотрудников: его видно в каталоге, его можно
     * продать вручную. Не виден только клиентам: витрина /shop и автоматический разбор заказов из
     * WhatsApp. {@link #hiddenFromClientsUntil} — необязательная дата, ПО которую включительно
     * товар скрыт; со следующего дня снова виден сам, без отдельной задачи по расписанию.
     */
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean hiddenFromClients = false;

    private LocalDate hiddenFromClientsUntil;

    /** Виден ли товар клиентам сегодня (см. {@link #hiddenFromClients}). */
    public boolean isVisibleToClients() {
        if (!active) return false;
        if (!hiddenFromClients) return true;
        return hiddenFromClientsUntil != null && LocalDate.now().isAfter(hiddenFromClientsUntil);
    }

    /**
     * Полуфабрикат (например, "Пряник" — испечён в день 1 без упаковки, сам идёт сырьём в
     * рецепт дня 2 "Пряники (16/30 шт)") — физически хранится и списывается как обычное сырьё
     * (склад "Сырье" в 1С), не как готовая продукция. У такого товара ОСТАЁТСЯ обычная
     * рецептура/заявка на производство (тот же механизм, что и у прочих товаров — см.
     * ProductionRequestService), но: 1) скрыт из витрины заказов и страницы "Остатки" (см.
     * ProductService.findAllActive/WarehouseStockController); 2) при оприходовании остаток
     * ЗЕРКАЛИРУЕТСЯ в одноимённое сырьё в справочнике "Сырьё" (см.
     * StockService.receiveProductForProduction) — именно оно указывается в рецепте товара дня
     * 2 как обычный ингредиент; 3) в 1С выгружается на счёт/склад сырья, а не готовой
     * продукции (см. OneCProductionSyncService). Колонка добавляется вручную через
     * ALTER TABLE ... DEFAULT false — тот же приём, что и у active выше.
     */
    @Column(nullable = false)
    private boolean semiFinished = false;

    /**
     * GUID номенклатуры в 1С (Catalog_Номенклатура.Ref_Key) — подбирается автоматически по
     * точному названию при первой выгрузке заказа с этим товаром в 1С (см. OneCOrderSyncService).
     */
    private String oneCNomenclatureKey;

    /**
     * Код номенклатуры в 1С — для колонки «Код» в бланке «Объём и масса», который приложение
     * собирает само, без 1С. Сверяется с 1С раз в сутки (OneCProductCodeSyncService).
     */
    private String oneCCode;

    /**
     * Счета учёта/субконто для табличной части "Товары" документа "Реализация товаров и
     * услуг" (JSON-объект с полями вида СчетУчетаБУ_Key, СубконтоДоходовБУ2 и т.д.) —
     * скопированы с последней найденной в 1С продажи этой номенклатуры, т.к. 1С OData не
     * заполняет их автоматически при создании документа (см. OneCOrderSyncService.resolveLineTemplate).
     */
    @Column(length = 4000)
    private String oneCLineTemplate;

    /**
     * GUID "верхней" спецификации номенклатуры в 1С (Catalog_СпецификацииНоменклатуры.Ref_Key) —
     * запоминается при последнем импорте рецепта (см. OneCRecipeImportService.importAll/
     * pickTopSpec) и подставляется в поле "Спецификация_Key" табличной части "Продукция"
     * документа "Отчёт производства за смену" (см. OneCProductionSyncService). Null, пока рецепт
     * товара ни разу не импортировался из 1С.
     */
    private String oneCSpecificationKey;

    /**
     * GUID подразделения организации в 1С (Catalog_ПодразделенияОрганизаций.Ref_Key) — в какой
     * производственный корпус (А/B/С/D) на самом деле идёт этот товар. Определяется по истории
     * РЕАЛЬНЫХ документов "Отчёт производства за смену", созданных вручную в 1С (не через CRM),
     * см. OneCDepartmentImportService — в отличие от единой константы
     * {@code crm.onec.production-department-key}, которую раньше CRM подставляла всем товарам
     * без разбора. Null, пока подразделение ни разу не импортировалось (тогда используется та
     * константа как запасной вариант, см. OneCProductionSyncService).
     */
    private String oneCDepartmentKey;
}
