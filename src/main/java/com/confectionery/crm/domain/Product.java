package com.confectionery.crm.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

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
     * Счета учёта/субконто для табличной части "Товары" документа "Реализация товаров и
     * услуг" (JSON-объект с полями вида СчетУчетаБУ_Key, СубконтоДоходовБУ2 и т.д.) —
     * скопированы с последней найденной в 1С продажи этой номенклатуры, т.к. 1С OData не
     * заполняет их автоматически при создании документа (см. OneCOrderSyncService.resolveLineTemplate).
     */
    @Column(length = 4000)
    private String oneCLineTemplate;

    /**
     * То же самое, что oneCLineTemplate, но для табличной части документа "Оприходование
     * товаров" (см. OneCReceiptSyncService) — поля счетов учёта там называются иначе
     * (КорСчетОприходованияБУ_Key и т.п.), поэтому кэш отдельный.
     */
    @Column(length = 4000)
    private String oneCReceiptTemplate;

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
