package com.confectionery.crm.domain;

public enum ProductionRequestStatus {
    NEW("Новая"),
    COLLECTED("Собрано"),
    RECEIVED("Оприходовано"),
    CANCELLED("Отменена");

    private final String label;

    ProductionRequestStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
