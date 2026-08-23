package com.confectionery.crm.domain;

public enum Role {
    ADMIN("Администратор"),
    SALES("Менеджер по продажам"),
    WAREHOUSE_FG("Кладовщик (склад ГП)"),
    ACCOUNTANT("Бухгалтер");

    private final String label;

    Role(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
