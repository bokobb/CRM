package com.confectionery.crm.domain;

import java.util.List;

/**
 * Куда идёт зарплата сотрудника (см. SalaryFundService):
 * BRIGADE — прямые затраты: ставка за смену, в себестоимость заявки, где он в бригаде;
 * PRODUCTION — накладные цеха: месячный оклад делится на дни и разносится по заявкам дня;
 * SALES — расходы на продажи (водители, грузчики, склад готовой продукции), не в себестоимость;
 * ADMIN — администрация (бухгалтерия, директор, оператор 1С, охрана), не в себестоимость.
 */
public enum EmployeeCostGroup {
    BRIGADE("Бригада"),
    PRODUCTION("Цех"),
    SALES("Продажи"),
    ADMIN("Администрация");

    private final String label;

    EmployeeCostGroup(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    private static final List<String> PRODUCTION_WORDS = List.of("технолог", "слесар", "механик", "электрик", "повар", "уборщ", "заведующ");
    private static final List<String> SALES_WORDS = List.of("водител", "экспедитор", "грузчик", "торгов", "продаж", "менеджер");
    private static final List<String> ADMIN_WORDS = List.of("бухгалтер", "директор", "оператор", "охран", "сторож", "кассир", "админ");

    /** Группа по должности, пока её не задали вручную; непонятная или пустая должность — бригада. */
    public static EmployeeCostGroup byPosition(String position) {
        String p = position == null ? "" : position.toLowerCase();
        if (ADMIN_WORDS.stream().anyMatch(p::contains)) return ADMIN;
        if (SALES_WORDS.stream().anyMatch(p::contains)) return SALES;
        if (PRODUCTION_WORDS.stream().anyMatch(p::contains)) return PRODUCTION;
        return BRIGADE;
    }
}
