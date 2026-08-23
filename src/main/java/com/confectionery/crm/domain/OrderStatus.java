package com.confectionery.crm.domain;

/**
 * CONFIRMED и IN_PRODUCTION остаются в перечислении ради модуля цехового производства
 * (ProductionTask), но в основном потоке дистрибьюторских заказов больше не используются:
 * готовая продукция всегда есть на складе, поэтому OrderService.confirm() ведёт сразу
 * NEW -> READY, без цеховых задач. DELIVERED = "отгружено со склада ГП" (см. OrderService.ship).
 */
public enum OrderStatus {
    NEW("Новый"),
    CONFIRMED("Подтверждён"),
    IN_PRODUCTION("В производстве"),
    READY("Готов к отгрузке"),
    DELIVERED("Отгружен"),
    CANCELLED("Отменён");

    private final String label;

    OrderStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
