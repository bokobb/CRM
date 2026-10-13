package com.confectionery.crm.domain;

/** Событие в истории заказа (OrderEvent): смена статуса или одно из трёх событий без смены статуса. */
public enum OrderEventType {
    STATUS("Статус"),
    MOVE("Выдача перенесена"),
    EDIT("Заказ изменён"),
    PAY("Оплата отмечена");

    private final String label;

    OrderEventType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
