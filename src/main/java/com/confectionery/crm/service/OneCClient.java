package com.confectionery.crm.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Клиент к стандартному OData-интерфейсу 1С:Fresh ({@code /odata/standard.odata/}). Методы
 * чтения read-only. Остатки берём из регистра бухгалтерии по счетам плана счетов (см.
 * OneCStockSyncService) — не из регистра накопления "Товары организаций", тот в этой базе не
 * ведётся (документы производства его не двигают, проверено вручную через саму 1С —
 * "Материальная ведомость" совпадает именно с бухгалтерским регистром). Запись в 1С —
 * {@link #createDraftSale}/{@link #updateDraftSale}/{@link #postDocument} для документа
 * "Реализация товаров и услуг" по заказу CRM (см. OneCOrderSyncService, черновик до явного
 * проведения); {@link #createReceiptDocument} для документа "Оприходование товаров" по
 * сводному РУЧНОМУ приходу готовой продукции за день (см. OneCReceiptSyncService — этот, в
 * отличие от "Реализации", создаётся сразу проведённым); {@link #createProductionReport}/
 * {@link #createMaterialsRequisition} для пары "Отчёт производства за смену"/"Требование-
 * накладная" по заявке на производство (см. OneCProductionSyncService, тоже черновики).
 * {@link #postDocument} и {@link #fetchPosted} общие для всех типов документов, которые CRM
 * создаёт непроведёнными — никогда не трогаем документ, который уже кто-то провёл сам.
 */
@Slf4j
@Component
class OneCClient {

    private static final int PAGE_SIZE = 1000;

    private final RestClient restClient;
    private final boolean configured;

    OneCClient(@Value("${crm.onec.base-url:}") String baseUrl,
               @Value("${crm.onec.username:}") String username,
               @Value("${crm.onec.password:}") String password) {
        this.configured = !baseUrl.isBlank() && !username.isBlank() && !password.isBlank();
        this.restClient = configured
                ? RestClient.builder()
                        .baseUrl(baseUrl + "/odata/standard.odata")
                        .defaultHeaders(h -> h.setBasicAuth(username, password))
                        .build()
                : null;
    }

    boolean isConfigured() {
        return configured;
    }

    /** Ref_Key счёта плана счетов по его коду (например "1320") — null, если не найден. */
    String findAccountKey(String code) {
        JsonNode response = restClient.get()
                .uri(b -> b.path("/ChartOfAccounts_Типовой")
                        .queryParam("$format", "json")
                        .queryParam("$filter", "Code eq '" + code + "'")
                        .queryParam("$select", "Ref_Key")
                        .build())
                .retrieve()
                .body(JsonNode.class);
        JsonNode values = response.path("value");
        if (values.isEmpty()) return null;
        String key = values.get(0).path("Ref_Key").asText("");
        return key.isBlank() ? null : key;
    }

    /** Справочник номенклатуры целиком: Ref_Key -> Description. */
    Map<String, String> fetchNomenclatureNames() {
        Map<String, String> names = new HashMap<>();
        int skip = 0;
        while (true) {
            final int currentSkip = skip;
            JsonNode response = restClient.get()
                    .uri(b -> b.path("/Catalog_Номенклатура")
                            .queryParam("$format", "json")
                            .queryParam("$select", "Ref_Key,Description")
                            .queryParam("$top", PAGE_SIZE)
                            .queryParam("$skip", currentSkip)
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
            JsonNode values = response.path("value");
            for (JsonNode item : values) {
                names.put(item.path("Ref_Key").asText(""), item.path("Description").asText(""));
            }
            if (values.size() < PAGE_SIZE) break;
            skip += PAGE_SIZE;
        }
        return names;
    }

    /** Все строки остатков регистра бухгалтерии (все счета, все склады) — фильтрация на стороне сервиса. */
    List<AccountBalanceRow> fetchAccountBalances() {
        List<AccountBalanceRow> rows = new ArrayList<>();
        int skip = 0;
        while (true) {
            final int currentSkip = skip;
            JsonNode response = restClient.get()
                    .uri(b -> b.path("/AccountingRegister_Типовой/Balance")
                            .queryParam("$format", "json")
                            .queryParam("$top", PAGE_SIZE)
                            .queryParam("$skip", currentSkip)
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
            JsonNode values = response.path("value");
            for (JsonNode item : values) {
                String nomenclatureType = item.path("ExtDimension1_Type").asText("");
                if (!"StandardODATA.Catalog_Номенклатура".equals(nomenclatureType)) {
                    continue; // остальные субконто (контрагенты, договоры и т.д.) нас не интересуют
                }
                rows.add(new AccountBalanceRow(
                        item.path("Account_Key").asText(""),
                        item.path("ExtDimension1").asText(""),
                        item.path("ExtDimension2").asText(""),
                        item.path("КоличествоBalance").decimalValue()
                ));
            }
            if (values.size() < PAGE_SIZE) break;
            skip += PAGE_SIZE;
        }
        return rows;
    }

    /** Все спецификации номенклатуры (рецепты) целиком, вместе с табличной частью "Исходные комплектующие". */
    List<Specification> fetchSpecifications() {
        List<Specification> specs = new ArrayList<>();
        int skip = 0;
        while (true) {
            final int currentSkip = skip;
            JsonNode response = restClient.get()
                    .uri(b -> b.path("/Catalog_СпецификацииНоменклатуры")
                            .queryParam("$format", "json")
                            .queryParam("$top", PAGE_SIZE)
                            .queryParam("$skip", currentSkip)
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
            JsonNode values = response.path("value");
            for (JsonNode item : values) {
                List<SpecLine> lines = new ArrayList<>();
                for (JsonNode line : item.path("ИсходныеКомплектующие")) {
                    lines.add(new SpecLine(line.path("Номенклатура_Key").asText(""), line.path("Количество").decimalValue()));
                }
                specs.add(new Specification(
                        item.path("Ref_Key").asText(""),
                        item.path("Owner_Key").asText(""),
                        item.path("Description").asText(""),
                        item.path("Количество").decimalValue(),
                        lines
                ));
            }
            if (values.size() < PAGE_SIZE) break;
            skip += PAGE_SIZE;
        }
        return specs;
    }

    /** Все контрагенты целиком, вместе с GUID основного договора (см. OneCOrderSyncService/OneCClientLinkService). */
    List<Contragent> fetchContragents() {
        List<Contragent> result = new ArrayList<>();
        int skip = 0;
        while (true) {
            final int currentSkip = skip;
            JsonNode response = restClient.get()
                    .uri(b -> b.path("/Catalog_Контрагенты")
                            .queryParam("$format", "json")
                            .queryParam("$select", "Ref_Key,Description,ОсновнойДоговорКонтрагента_Key")
                            .queryParam("$top", PAGE_SIZE)
                            .queryParam("$skip", currentSkip)
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
            JsonNode values = response.path("value");
            for (JsonNode item : values) {
                result.add(new Contragent(
                        item.path("Ref_Key").asText(""),
                        item.path("Description").asText(""),
                        item.path("ОсновнойДоговорКонтрагента_Key").asText("")
                ));
            }
            if (values.size() < PAGE_SIZE) break;
            skip += PAGE_SIZE;
        }
        return result;
    }

    /**
     * Справочник "Типы цен номенклатуры" целиком: Ref_Key -> Description. В этой базе у
     * каждого клиента-дистрибьютора свой именной тип цен (см. OneCClientPriceSyncService) —
     * ровно так же организован Контрагент/договор ниже, но это отдельный справочник.
     */
    Map<String, String> fetchPriceTypeNames() {
        Map<String, String> names = new HashMap<>();
        int skip = 0;
        while (true) {
            final int currentSkip = skip;
            JsonNode response = restClient.get()
                    .uri(b -> b.path("/Catalog_ТипыЦенНоменклатуры")
                            .queryParam("$format", "json")
                            .queryParam("$select", "Ref_Key,Description")
                            .queryParam("$top", PAGE_SIZE)
                            .queryParam("$skip", currentSkip)
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
            JsonNode values = response.path("value");
            for (JsonNode item : values) {
                names.put(item.path("Ref_Key").asText(""), item.path("Description").asText(""));
            }
            if (values.size() < PAGE_SIZE) break;
            skip += PAGE_SIZE;
        }
        return names;
    }

    /** Все строки регистра цен номенклатуры для одного типа цен (все периоды, см. OneCClientPriceSyncService). */
    List<PriceRow> fetchPricesByType(String priceTypeKey) {
        List<PriceRow> rows = new ArrayList<>();
        JsonNode response = restClient.get()
                .uri(b -> b.path("/InformationRegister_ЦеныНоменклатуры_RecordType")
                        .queryParam("$format", "json")
                        .queryParam("$filter", "ТипЦен_Key eq guid'" + priceTypeKey + "'")
                        .queryParam("$select", "Period,Номенклатура_Key,Цена,Active")
                        .queryParam("$top", PAGE_SIZE)
                        .build())
                .retrieve()
                .body(JsonNode.class);
        for (JsonNode item : response.path("value")) {
            rows.add(new PriceRow(
                    item.path("Номенклатура_Key").asText(""),
                    item.path("Цена").decimalValue(),
                    item.path("Period").asText(""),
                    item.path("Active").asBoolean(false)
            ));
        }
        return rows;
    }

    /**
     * Счета учёта/субконто последней найденной строки продажи этой номенклатуры (документ
     * "Реализация товаров и услуг") — 1С OData не заполняет их сама при создании документа
     * (это делают клиентские скрипты формы, которые OData не запускает), поэтому берём готовый
     * шаблон с реальной, когда-то корректно проведённой строки. Null, если этой номенклатуры
     * ещё не было ни в одной продаже — см. {@link #findAnyLineTemplate} для этого случая.
     */
    Map<String, Object> findLineTemplate(String nomenclatureKey) {
        JsonNode response = restClient.get()
                .uri(b -> b.path("/Document_РеализацияТоваровУслуг_Товары")
                        .queryParam("$format", "json")
                        .queryParam("$filter", "Номенклатура_Key eq guid'" + nomenclatureKey + "'")
                        .queryParam("$top", 1)
                        .build())
                .retrieve()
                .body(JsonNode.class);
        JsonNode values = response.path("value");
        return values.isEmpty() ? null : extractTemplate(values.get(0), SALE_LINE_OWN_FIELDS);
    }

    /**
     * Шаблон-заготовка с ЛЮБОЙ строки ЛЮБОЙ продажи (неважно, какой номенклатуры) — счета
     * учёта (СчетУчетаБУ/НУ, СчетДоходовБУ/НУ, СчетСписанияСебестоимостиБУ/НУ,
     * ЕдиницаИзмерения и т.д.) в этой базе одинаковы для всех товаров, отличается только
     * аналитика по номенклатурной группе/номенклатуре (см. OneCOrderSyncService —
     * подставляет группу и саму номенклатуру товара поверх этой заготовки, когда у конкретной
     * номенклатуры ещё не было собственных продаж). Null, если в 1С вообще нет ни одной
     * "Реализации" — тогда неоткуда взять даже заготовку.
     */
    Map<String, Object> findAnyLineTemplate() {
        JsonNode response = restClient.get()
                .uri(b -> b.path("/Document_РеализацияТоваровУслуг_Товары")
                        .queryParam("$format", "json")
                        .queryParam("$top", 1)
                        .build())
                .retrieve()
                .body(JsonNode.class);
        JsonNode values = response.path("value");
        return values.isEmpty() ? null : extractTemplate(values.get(0), SALE_LINE_OWN_FIELDS);
    }

    // Поля, которые OneCOrderSyncService подставляет сам под конкретный заказ — из шаблона их не берём.
    private static final List<String> SALE_LINE_OWN_FIELDS = List.of("Ref_Key", "LineNumber", "КлючСвязи",
            "Номенклатура_Key", "Количество", "Цена", "Сумма");

    private static Map<String, Object> extractTemplate(JsonNode row, List<String> ownFields) {
        // "@" отсеивает служебные аннотации OData вроде "Номенклатура@navigationLinkUrl" — это
        // не данные документа, а ссылки на связанные сущности, которые GET подмешивает в
        // объект; отправлять их обратно в 1С нельзя.
        Map<String, Object> template = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> field : row.properties()) {
            String key = field.getKey();
            JsonNode value = field.getValue();
            if (ownFields.contains(key) || key.indexOf('@') >= 0) continue;
            // Табличные части документа (Товары/Услуги/ОС/УчастникиСовместнойДеятельности и
            // т.п.) приходят как JSON-массивы — не скалярные поля шапки/строки, копировать их
            // в шаблон нельзя. Раньше они всё равно попадали в шаблон как null (jsonToJava
            // возвращает null для не-value-узлов) — 1С отклоняла документ с 400 Bad Request,
            // потому что null для табличной части — не то же самое, что её отсутствие в теле
            // запроса (обнаружено на закупке №13: 1С вернула ошибку по полю
            // "УчастникиСовместнойДеятельности"). Полностью пропускаем такие поля — 1С сама
            // подставит пустую табличную часть по умолчанию.
            if (value.isArray() || value.isObject()) continue;
            template.put(key, jsonToJava(value, key));
        }
        return template;
    }

    /** Ref_Key номенклатурной группы товара (Catalog_Номенклатура.НоменклатурнаяГруппа_Key) — null, если не задана. */
    String fetchNomenclatureGroup(String nomenclatureKey) {
        JsonNode response = restClient.get()
                .uri(b -> b.path("/Catalog_Номенклатура(guid'" + nomenclatureKey + "')")
                        .queryParam("$format", "json")
                        .queryParam("$select", "НоменклатурнаяГруппа_Key")
                        .build())
                .retrieve()
                .body(JsonNode.class);
        String key = response.path("НоменклатурнаяГруппа_Key").asText("");
        return key.isBlank() ? null : key;
    }

    /** Ref_Key базовой единицы измерения номенклатуры (Catalog_Номенклатура.БазоваяЕдиницаИзмерения_Key) — null, если не задана. */
    String fetchNomenclatureUnit(String nomenclatureKey) {
        JsonNode response = restClient.get()
                .uri(b -> b.path("/Catalog_Номенклатура(guid'" + nomenclatureKey + "')")
                        .queryParam("$format", "json")
                        .queryParam("$select", "БазоваяЕдиницаИзмерения_Key")
                        .build())
                .retrieve()
                .body(JsonNode.class);
        String key = response.path("БазоваяЕдиницаИзмерения_Key").asText("");
        return key.isBlank() ? null : key;
    }

    private static final String ZERO_GUID = "00000000-0000-0000-0000-000000000000";

    /**
     * Пустая строка у поля "*_Key"/"Субконто*" (не "*_Type") означает неиспользуемый слот
     * субконто (когда у счёта меньше 3 уровней аналитики) — GET отдаёт его как "", но 1С
     * принимает на запись только настоящий GUID, поэтому подставляем нулевой (проверено:
     * именно так выглядит этот же слот в реально существующих строках других номенклатур).
     */
    private static Object jsonToJava(JsonNode node, String key) {
        if (node.isNull() || node.isMissingNode()) return null;
        // Табличные части документа (Товары/Услуги/ОС и т.п.) приходят как JSON-массивы —
        // не скалярные поля шапки/строки, копировать их в шаблон нельзя (и не нужно). Раньше
        // здесь падало: в Jackson 3 asText()/asString() на ArrayNode/ObjectNode бросает
        // исключение вместо тихого возврата "", как было в Jackson 2.
        if (!node.isValueNode()) return null;
        if (node.isBoolean()) return node.booleanValue();
        if (node.isNumber()) return node.numberValue();
        String text = node.asText();
        if (text.isEmpty() && !key.endsWith("_Type")) return ZERO_GUID;
        return text;
    }

    /** Создаёт документ "Реализация товаров и услуг" (Posted:false — не проведён, для проверки бухгалтером). */
    CreatedDocument createDraftSale(Map<String, Object> body) {
        JsonNode response = restClient.post()
                .uri(b -> b.path("/Document_РеализацияТоваровУслуг")
                        .queryParam("$format", "json")
                        .build())
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        return new CreatedDocument(response.path("Ref_Key").asText(""), response.path("Number").asText(""));
    }

    /** true, если документ уже проведён в 1С — перед PATCH проверяем, чтобы не разойтись с проводками (см. OneCOrderSyncService). */
    boolean fetchPosted(String entitySet, String ref) {
        JsonNode response = restClient.get()
                .uri(b -> b.path("/" + entitySet + "(guid'" + ref + "')")
                        .queryParam("$format", "json")
                        .queryParam("$select", "Posted")
                        .build())
                .retrieve()
                .body(JsonNode.class);
        return response.path("Posted").asBoolean(false);
    }

    /** Перезаписывает НЕпроведённый документ (шапку и "Товары" целиком) — см. OneCOrderSyncService.trySyncOrder. */
    void updateDraftSale(String ref, Map<String, Object> body) {
        restClient.patch()
                .uri(b -> b.path("/Document_РеализацияТоваровУслуг(guid'" + ref + "')")
                        .queryParam("$format", "json")
                        .build())
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * Перезаписывает НЕпроведённый документ любого типа (шапку и табличную часть целиком) —
     * общий вариант {@link #updateDraftSale} для остальных документов, которые CRM держит
     * актуальными до проведения (см. OneCProductionSyncService.trySyncRequest).
     */
    void updateDocument(String entitySet, String ref, Map<String, Object> body) {
        restClient.patch()
                .uri(b -> b.path("/" + entitySet + "(guid'" + ref + "')")
                        .queryParam("$format", "json")
                        .build())
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * Ставит/снимает флаг "Пометка удаления" — сам документ не трогает (не проведён/проведён,
     * состав и суммы остаются как есть), физического удаления 1С этим не делает: реальное
     * удаление помеченных объектов — отдельная операция, которую бухгалтер выполняет в 1С сам
     * (и она откажет, если документ проведён и есть зависимые движения). См. OneCOrderSyncService.trySyncCancel.
     */
    void markForDeletion(String entitySet, String ref, boolean mark) {
        restClient.patch()
                .uri(b -> b.path("/" + entitySet + "(guid'" + ref + "')")
                        .queryParam("$format", "json")
                        .build())
                .body(Map.of("DeletionMark", mark))
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * Проводит документ (Posted:true) — в отличие от простого выставления флага, платформа
     * 1С при записи объекта с изменившимся Posted сама выполняет "обработку проведения"
     * документа (формирует реальные движения по регистрам), в том числе и через OData —
     * это не косметическая пометка. Если проведение не проходит по бизнес-правилам 1С
     * (не хватает данных, остатков и т.п.) — 1С вернёт ошибку, документ останется непроведённым.
     * {@code entitySet} — имя набора документа (например, "Document_РеализацияТоваровУслуг"),
     * т.к. этот метод общий для всех типов документов, которые CRM создаёт непроведёнными.
     * См. OneCOrderSyncService.postEligibleOrders / OneCProductionSyncService.
     */
    void postDocument(String entitySet, String ref) {
        restClient.patch()
                .uri(b -> b.path("/" + entitySet + "(guid'" + ref + "')")
                        .queryParam("$format", "json")
                        .build())
                .body(Map.of("Posted", true))
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * Шаблон-заготовка для табличной части документа "Оприходование товаров" — та же идея,
     * что и {@link #findAnyLineTemplate()} для "Реализации": счета учёта (СчетУчетаБУ/НУ,
     * КорСчетОприходованияБУ/НУ, ЕдиницаИзмерения) одинаковы для всех номенклатур в этой
     * базе, различается только субконто по номенклатурной группе/номенклатуре (см.
     * OneCReceiptSyncService — подставляет их поверх заготовки). Null, если в 1С вообще нет
     * ни одного такого документа.
     */
    Map<String, Object> findAnyReceiptLineTemplate() {
        JsonNode response = restClient.get()
                .uri(b -> b.path("/Document_ОприходованиеТоваров_Товары")
                        .queryParam("$format", "json")
                        .queryParam("$top", 1)
                        .build())
                .retrieve()
                .body(JsonNode.class);
        JsonNode values = response.path("value");
        if (values.isEmpty()) return null;
        return extractTemplate(values.get(0),
                List.of("Ref_Key", "LineNumber", "Номенклатура_Key", "Количество", "Цена", "Сумма"));
    }

    /**
     * Шаблон-заготовка шапки документа "Поступление товаров и услуг" (счета учёта расчётов с
     * контрагентом, вид операции, аналитика НДС и т.п.) — с ЛЮБОГО существующего документа
     * этого типа в базе, по той же идее, что {@link #findAnyLineTemplate()} и
     * {@link #findAnyReceiptLineTemplate()}: не гадаем GUID-ы констант шапки руками, а
     * копируем их с реального документа. Null, если в 1С ещё нет ни одного такого документа.
     */
    Map<String, Object> findAnyGoodsReceiptTemplate() {
        JsonNode response = restClient.get()
                .uri(b -> b.path("/Document_ПоступлениеТоваровУслуг")
                        .queryParam("$format", "json")
                        .queryParam("$top", 1)
                        .build())
                .retrieve()
                .body(JsonNode.class);
        JsonNode values = response.path("value");
        if (values.isEmpty()) return null;
        // ВидОперации ("Товары"/"Услуги"/"ОсновныеСредства" и т.п.) — тоже исключаем из
        // копирования: "любой" документ в базе может оказаться не тем видом операции (так и
        // случилось на реальной базе — первый попавшийся был "ОсновныеСредства", закупка
        // сырья ушла не в тот раздел 1С и без товаров в табличной части). Проставляется явно
        // в OneCPurchaseSyncService.
        return extractTemplate(values.get(0), List.of("Ref_Key", "Number", "Date", "Posted", "DeletionMark",
                "Организация_Key", "Контрагент_Key", "ДоговорКонтрагента_Key", "Склад_Key", "Комментарий", "Товары",
                "ВидОперации"));
    }

    /**
     * Шаблон-заготовка строки табличной части "Товары" документа "Поступление товаров и
     * услуг" — см. {@link #findAnyGoodsReceiptTemplate()}. Null, если в 1С ещё нет ни одного
     * такого документа.
     */
    Map<String, Object> findAnyGoodsReceiptLineTemplate() {
        JsonNode response = restClient.get()
                .uri(b -> b.path("/Document_ПоступлениеТоваровУслуг_Товары")
                        .queryParam("$format", "json")
                        .queryParam("$top", 1)
                        .build())
                .retrieve()
                .body(JsonNode.class);
        JsonNode values = response.path("value");
        if (values.isEmpty()) return null;
        return extractTemplate(values.get(0),
                List.of("Ref_Key", "LineNumber", "Номенклатура_Key", "Количество", "Цена", "Сумма"));
    }

    /** Создаёт и сразу проводит (Posted:true) документ "Поступление товаров и услуг" — см. OneCPurchaseSyncService. */
    CreatedDocument createGoodsReceiptDocument(Map<String, Object> body) {
        JsonNode response = restClient.post()
                .uri(b -> b.path("/Document_ПоступлениеТоваровУслуг")
                        .queryParam("$format", "json")
                        .build())
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        return new CreatedDocument(response.path("Ref_Key").asText(""), response.path("Number").asText(""));
    }

    /** Создаёт и сразу проводит (Posted:true) документ "Оприходование товаров" — см. OneCReceiptSyncService. */
    CreatedDocument createReceiptDocument(Map<String, Object> body) {
        JsonNode response = restClient.post()
                .uri(b -> b.path("/Document_ОприходованиеТоваров")
                        .queryParam("$format", "json")
                        .build())
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        return new CreatedDocument(response.path("Ref_Key").asText(""), response.path("Number").asText(""));
    }

    /**
     * Создаёт НЕпроведённый документ "Отчёт производства за смену" (готовая продукция от
     * заявки на производство) — см. OneCProductionSyncService.
     */
    CreatedDocument createProductionReport(Map<String, Object> body) {
        JsonNode response = restClient.post()
                .uri(b -> b.path("/Document_ОтчетПроизводстваЗаСмену")
                        .queryParam("$format", "json")
                        .build())
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        return new CreatedDocument(response.path("Ref_Key").asText(""), response.path("Number").asText(""));
    }

    /**
     * Создаёт НЕпроведённый документ "Требование-накладная" (списание сырья заявки на
     * производство, ссылается на "Отчёт производства за смену" через ДокументОснование) —
     * см. OneCProductionSyncService.
     */
    CreatedDocument createMaterialsRequisition(Map<String, Object> body) {
        JsonNode response = restClient.post()
                .uri(b -> b.path("/Document_ТребованиеНакладная")
                        .queryParam("$format", "json")
                        .build())
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        return new CreatedDocument(response.path("Ref_Key").asText(""), response.path("Number").asText(""));
    }

    /**
     * Файл, прикреплённый вручную к документу "Реализация" в самой 1С (обычно — накладная,
     * которую распечатали/подписали и приложили к документу) — берём самый свежий, если их
     * несколько. Null, если к документу ещё ничего не приложено (см. OneCInvoiceService).
     */
    AttachedFile fetchAttachedFile(String documentRef) {
        JsonNode response = restClient.get()
                .uri(b -> b.path("/Catalog_РеализацияТоваровУслугПрисоединенныеФайлы")
                        .queryParam("$format", "json")
                        .queryParam("$filter", "ВладелецФайла_Key eq guid'" + documentRef + "'")
                        .queryParam("$select", "Description,Расширение,ДатаСоздания,ФайлХранилище_Base64Data")
                        .build())
                .retrieve()
                .body(JsonNode.class);
        JsonNode values = response.path("value");
        if (values.isEmpty()) return null;

        JsonNode latest = values.get(0);
        for (JsonNode file : values) {
            if (file.path("ДатаСоздания").asText("").compareTo(latest.path("ДатаСоздания").asText("")) > 0) {
                latest = file;
            }
        }
        String base64 = latest.path("ФайлХранилище_Base64Data").asText("");
        if (base64.isBlank()) return null;
        return new AttachedFile(
                Base64.getDecoder().decode(base64),
                latest.path("Расширение").asText("pdf"),
                latest.path("Description").asText("Накладная")
        );
    }

    record Contragent(String ref, String description, String contractKey) {
    }

    record AttachedFile(byte[] data, String extension, String name) {
    }

    /** Строка регистра цен номенклатуры: period — ISO-строка даты/времени 1С, как пришла из OData. */
    record PriceRow(String nomenclatureKey, BigDecimal price, String period, boolean active) {
    }

    record CreatedDocument(String ref, String number) {
    }

    record AccountBalanceRow(String accountKey, String nomenclatureKey, String warehouseKey, BigDecimal quantity) {
    }

    record SpecLine(String ingredientKey, BigDecimal quantity) {
    }

    record Specification(String ref, String ownerKey, String description, BigDecimal yieldQuantity, List<SpecLine> lines) {
    }
}
