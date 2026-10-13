package com.confectionery.crm.service;

import com.confectionery.crm.domain.Order;
import com.confectionery.crm.domain.OrderSource;
import com.confectionery.crm.domain.OrderStatus;
import com.confectionery.crm.domain.Role;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.confectionery.crm.domain.OrderStatus.*;

/**
 * Какие переходы статуса заказа возможны и кому. Кнопки действий в карточке заказа админки
 * строятся из {@link #availableTransitions}; сам переход выполняет OrderService.transition,
 * который сначала спрашивает {@link #check}. Те же правила, что у REST API приложения.
 *
 * <pre>
 * PLACED «Новая» ─принять→ CONFIRMED «Принят» ─начать сборку→ NEW «Собирается» ─готов→ READY
 * READY ─выдать→ DELIVERED ─(оплата отмечена / закрыть)→ COMPLETED
 * READY ─клиент не пришёл→ NO_SHOW ─перенести выдачу→ READY
 * любой, кроме выданного, ─отменить→ CANCELLED (выданный — только директор/бухгалтер)
 * </pre>
 */
@Component
public class OrderStateMachine {

    /** Переход: в какой статус, подпись кнопки, вид кнопки (primary/soft/danger). */
    public record Transition(OrderStatus target, String label, String kind) {
    }

    private static final Map<OrderStatus, Set<OrderStatus>> NEXT = Map.of(
            PLACED, EnumSet.of(CONFIRMED, READY, CANCELLED),
            CONFIRMED, EnumSet.of(NEW, READY, CANCELLED),
            NEW, EnumSet.of(READY, CANCELLED),
            IN_PRODUCTION, EnumSet.of(READY, CANCELLED),
            READY, EnumSet.of(DELIVERED, NO_SHOW, NEW, CANCELLED),
            NO_SHOW, EnumSet.of(READY, DELIVERED, CANCELLED),
            DELIVERED, EnumSet.of(COMPLETED, CANCELLED),
            COMPLETED, EnumSet.of(CANCELLED),
            CANCELLED, EnumSet.noneOf(OrderStatus.class));

    private static final Map<OrderStatus, Set<Role>> WHO = Map.of(
            CONFIRMED, EnumSet.of(Role.ADMIN, Role.SALES, Role.WAREHOUSE_FG),
            NEW, EnumSet.of(Role.ADMIN, Role.WAREHOUSE_FG, Role.SALES, Role.ACCOUNTANT),
            READY, EnumSet.of(Role.ADMIN, Role.WAREHOUSE_FG),
            NO_SHOW, EnumSet.of(Role.ADMIN, Role.WAREHOUSE_FG, Role.SALES),
            DELIVERED, EnumSet.of(Role.ADMIN, Role.WAREHOUSE_FG),
            COMPLETED, EnumSet.of(Role.ADMIN, Role.ACCOUNTANT),
            CANCELLED, EnumSet.of(Role.ADMIN, Role.SALES, Role.ACCOUNTANT, Role.WAREHOUSE_FG));

    public boolean canTransition(Order order, OrderStatus target, Role role) {
        return check(order, target, role) == null;
    }

    /** null — переход разрешён, иначе понятная причина отказа. */
    public String check(Order order, OrderStatus target, Role role) {
        OrderStatus from = order.getStatus();
        if (!NEXT.getOrDefault(from, Set.of()).contains(target)) {
            return "Из статуса «" + from.badgeLabel() + "» в «" + target.badgeLabel() + "» перейти нельзя";
        }
        if (role == null || !WHO.getOrDefault(target, Set.of()).contains(role)) {
            return "Это действие вам недоступно";
        }
        if (target == CANCELLED && from.isShipped() && role != Role.ADMIN && role != Role.ACCOUNTANT) {
            return "Выданный заказ может отменить только директор или бухгалтер";
        }
        if (target == READY && from == NO_SHOW) {
            return "Чтобы вернуть заказ к выдаче, перенесите выдачу на новое окно";
        }
        return null;
    }

    /**
     * Кнопки карточки заказа — по одной на переход вперёд, как в макете: у «Новая» — принять, у
     * «Принят» — начать сборку, у «Собирается» — готов, у «Готов» — выдать и «клиент не пришёл»,
     * плюс отмена там, где её даёт макет. Перенос, изменение, повтор, возврат и оплата — отдельные
     * кнопки (см. AdminOrderController).
     */
    public List<Transition> availableTransitions(Order order, Role role) {
        List<Transition> out = new ArrayList<>();
        OrderStatus s = order.getStatus();
        switch (s) {
            case PLACED -> add(out, order, role, CONFIRMED, "Принять заказ", "primary");
            case CONFIRMED -> add(out, order, role, NEW, "Начать сборку", "primary");
            case NEW, IN_PRODUCTION -> add(out, order, role, READY, "Готов к выдаче", "primary");
            case READY -> {
                add(out, order, role, DELIVERED, "Выдать", "primary");
                add(out, order, role, NO_SHOW, "Клиент не пришёл", "soft");
            }
            default -> {
            }
        }
        if (s == PLACED || s == CONFIRMED || s == NEW || s == IN_PRODUCTION || s == NO_SHOW) {
            add(out, order, role, CANCELLED, "Отменить", "danger");
        }
        return out;
    }

    private void add(List<Transition> out, Order order, Role role, OrderStatus target, String label, String kind) {
        if (canTransition(order, target, role)) {
            out.add(new Transition(target, label, kind));
        }
    }

    /**
     * Куда откатит «Вернуть» (OrderService.rollback) — шаг назад; null — некуда. Откат — только
     * директору: это исправление ошибки, а не обычная работа.
     */
    public OrderStatus rollbackTarget(Order order, Role role) {
        if (role != Role.ADMIN) return null;
        boolean byClient = order.getSource() == OrderSource.SITE || order.getSource() == OrderSource.WHATSAPP;
        return switch (order.getStatus()) {
            case COMPLETED -> DELIVERED;
            case DELIVERED, NO_SHOW -> READY;
            case READY -> NEW;
            case NEW, IN_PRODUCTION -> CONFIRMED;
            case CONFIRMED -> byClient ? PLACED : null;
            case CANCELLED -> {
                OrderStatus before = OrderService.statusBeforeCancel(order);
                yield before != null ? before : NEW;
            }
            case PLACED -> null;
        };
    }

    /** Изменить состав, окно и оплату можно до начала сборки: «Новая» и «Принят». */
    public boolean canEdit(Order order, Role role) {
        return (order.getStatus() == PLACED || order.getStatus() == CONFIRMED)
                && role != null && EnumSet.of(Role.ADMIN, Role.SALES, Role.ACCOUNTANT, Role.WAREHOUSE_FG).contains(role);
    }

    public boolean canMove(Order order, Role role) {
        OrderStatus s = order.getStatus();
        return (s == PLACED || s == CONFIRMED || s == NEW || s == IN_PRODUCTION || s == READY || s == NO_SHOW)
                && role != null && EnumSet.of(Role.ADMIN, Role.SALES, Role.ACCOUNTANT, Role.WAREHOUSE_FG).contains(role);
    }

    /** «Отметить оплату» — только директор и бухгалтер; не у отменённого и не у новой заявки. */
    public boolean canMarkPaid(Order order, Role role) {
        return !order.isPaid() && order.getStatus() != CANCELLED && order.getStatus() != PLACED
                && (role == Role.ADMIN || role == Role.ACCOUNTANT);
    }
}
