package com.confectionery.crm.domain;

public enum Role {
    ADMIN("Администратор"),
    SALES("Менеджер по продажам"),
    WAREHOUSE_FG("Кладовщик (склад ГП)"),
    WAREHOUSE_RAW("Кладовщик (склад сырья)"),
    ACCOUNTANT("Бухгалтер"),
    /** Распределяет бригады на экране «Производство», ведёт сотрудников и техкарты; ставок не видит. */
    TECHNOLOGIST("Технолог");

    private final String label;

    Role(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
