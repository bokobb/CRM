package com.confectionery.crm.domain;

public enum ProductionTaskStatus {
    PENDING("Ожидает"),
    IN_PROGRESS("В работе"),
    DONE("Готово");

    private final String label;

    ProductionTaskStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
