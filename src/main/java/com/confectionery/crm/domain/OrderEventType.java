package com.confectionery.crm.domain;

/**
 * Событие в истории заказа (OrderEvent): смена статуса или событие без смены статуса. Возврат к заказу
 * (RETURN*) — дистрибьютор привёз возврат вместе с получением заказа, его сумма вычитается из заказа.
 * Новое значение — расширить CHECK в базе (scripts/sql/2026-10-14_order_event_return.sql).
 */
public enum OrderEventType {
    STATUS("Статус"),
    MOVE("Выдача перенесена"),
    EDIT("Заказ изменён"),
    PAY("Оплата отмечена"),
    RETURN("Принят возврат"),
    RETURN_EDIT("Возврат изменён"),
    RETURN_DELETE("Возврат удалён");

    private final String label;

    OrderEventType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
