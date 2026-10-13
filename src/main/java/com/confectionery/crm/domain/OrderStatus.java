package com.confectionery.crm.domain;

/**
 * Путь заказа: PLACED «Новая» (клиент оформил сам на /shop или в WhatsApp) → CONFIRMED «Принят»
 * (принят сотрудником, ещё не собирают; заказ сотрудника создаётся сразу здесь) → NEW «В процессе
 * сборки» → READY «Готов к выдаче» → DELIVERED «Отгружен» → COMPLETED «Завершён» (выдан и оплачен).
 * NO_SHOW «Не забрали» — заказ был готов, окно выдачи прошло, клиент не приехал (READY → NO_SHOW,
 * после переноса выдачи снова READY). CANCELLED — отменён из любого статуса.
 * IN_PRODUCTION в потоке не используется (готовая продукция всегда на складе) — значение оставлено,
 * потому что хранится в orders.status (CHECK-ограничение в БД) и может встретиться у старых заказов.
 *
 * В админке и у покупателя статусы сведены к семи плашкам — см. {@link #badge()}.
 */
public enum OrderStatus {
    PLACED("Новая"),
    CONFIRMED("Принят"),
    NEW("В процессе сборки"),
    IN_PRODUCTION("В производстве"),
    READY("Готов к отгрузке"),
    NO_SHOW("Не забрали"),
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

    /**
     * Плашка статуса в админке (класс st--{badge}) и ключ этапа в истории заказов /shop:
     * sent «Новая», new «Принят», work «Собирается», ready «Готов к выдаче», done «Выдан»,
     * noshow «Не забрали», off «Отменён».
     */
    public String badge() {
        return switch (this) {
            case PLACED -> "sent";
            case CONFIRMED -> "new";
            case NEW, IN_PRODUCTION -> "work";
            case READY -> "ready";
            case NO_SHOW -> "noshow";
            case DELIVERED, COMPLETED -> "done";
            case CANCELLED -> "off";
        };
    }

    /** Подпись плашки — та же, что видит покупатель, плюс «Не забрали» для сотрудников. */
    public String badgeLabel() {
        return badgeLabel(badge());
    }

    public static String badgeLabel(String badge) {
        return switch (badge) {
            case "sent" -> "Новая";
            case "new" -> "Принят";
            case "work" -> "Собирается";
            case "ready" -> "Готов к выдаче";
            case "done" -> "Выдан";
            case "noshow" -> "Не забрали";
            case "off" -> "Отменён";
            default -> badge;
        };
    }

    /** Статусы CRM, которые показываются плашкой {@code badge}. */
    public static java.util.List<OrderStatus> ofBadge(String badge) {
        return java.util.Arrays.stream(values()).filter(s -> s.badge().equals(badge)).toList();
    }

    public boolean isShipped() {
        return this == DELIVERED || this == COMPLETED;
    }

    /** Заказ ещё в работе: не выдан и не отменён. */
    public boolean isActive() {
        return !isShipped() && this != CANCELLED;
    }
}
