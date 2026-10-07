package com.confectionery.crm.domain;

/**
 * CONFIRMED и IN_PRODUCTION в основном потоке заказов не используются: готовая продукция
 * всегда есть на складе, поэтому OrderService.confirm() ведёт сразу NEW -> READY. Значения
 * оставлены, потому что хранятся в orders.status (CHECK-ограничение в БД) и могут встретиться
 * у старых заказов. DELIVERED = "отгружено со склада ГП" (см. OrderService.ship).
 */
public enum OrderStatus {
    NEW("В процессе сборки"),
    CONFIRMED("Подтверждён"),
    IN_PRODUCTION("В производстве"),
    READY("Готов к отгрузке"),
    DELIVERED("Отгружен"),
    COMPLETED("Завершён"),
    CANCELLED("Отменён");

    private final String label;

    OrderStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
