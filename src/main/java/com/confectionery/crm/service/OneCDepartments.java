package com.confectionery.crm.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Производственные подразделения (корпуса) в 1С — GUID-ы из справочника
 * "Подразделения организаций" (Catalog_ПодразделенияОрганизаций), статичны (этот справочник у
 * клиента не меняется). Захардкожены, а не подгружаются из 1С через OData на каждое открытие
 * формы товара — только 4 значения, и они не редактируются в самой 1С годами. См.
 * ProductController (выпадающий список в карточке товара) и OneCDepartmentImportService
 * (автоматическое определение по истории документов).
 */
public final class OneCDepartments {

    public static final Map<String, String> NAMES = new LinkedHashMap<>();
    static {
        NAMES.put("8e8ccd44-7380-11f1-8dca-5254007c5efd", "Производство Корпус А");
        NAMES.put("778f254e-66ef-11f1-82c6-5254007c5efd", "Производство Корпус B");
        NAMES.put("b502dd92-7380-11f1-8dca-5254007c5efd", "Производство Корпус С");
        NAMES.put("e72d5a7c-7380-11f1-8dca-5254007c5efd", "Производство Корпус D");
    }

    private OneCDepartments() {
    }
}
