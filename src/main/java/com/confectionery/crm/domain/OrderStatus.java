package com.confectionery.crm.domain;

public enum OrderStatus {
    NEW("Новый"),
    CONFIRMED("Подтверждён"),
    IN_PRODUCTION("В производстве"),
    READY("Готов"),
    DELIVERED("Выдан"),
    CANCELLED("Отменён");

    private final String label;

    OrderStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
