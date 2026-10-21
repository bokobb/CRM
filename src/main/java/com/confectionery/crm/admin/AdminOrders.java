package com.confectionery.crm.admin;

import com.confectionery.crm.domain.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Как заказ выглядит в админке: плашка, источник, день и окно выдачи, коробки/вес/объём, оплата,
 * история. Логика — как в макете (admin-maket.js: tot, payInfo, winCell, история).
 */
public final class AdminOrders {

    private AdminOrders() {
    }

    private static final Pattern UNITS_IN_NAME = Pattern.compile("\\((\\d+)(?:\\s*/\\s*(\\d+))?\\s*шт\\.?\\)");

    /** Штук в коробке: из карточки товара, иначе из названия «(12 шт)»; null — неизвестно. */
    public static BigDecimal boxUnits(Product p) {
        if (p.getUnitsPerBox() != null && p.getUnitsPerBox().signum() > 0) return p.getUnitsPerBox();
        Matcher m = UNITS_IN_NAME.matcher(p.getName() == null ? "" : p.getName());
        return m.find() ? new BigDecimal(m.group(1)) : null;
    }

    /** Вес одной упаковки, граммы: нетто, иначе брутто; null — не задан. */
    public static BigDecimal grams(Product p) {
        if (p.getNetWeightGrams() != null && p.getNetWeightGrams().signum() > 0) return p.getNetWeightGrams();
        if (p.getUnitWeightKg() != null && p.getUnitWeightKg().signum() > 0) return p.getUnitWeightKg().movePointRight(3);
        return null;
    }

    public static String photo(Product p) {
        return p.getImageUrl() == null || p.getImageUrl().isBlank() ? null : p.getImageUrl();
    }

    /** День выдачи: выбранный (dueDate), у заказов без него — день отгрузки или оформления. */
    public static LocalDate day(Order o) {
        if (o.getDueDate() != null) return o.getDueDate();
        if (o.getShippedAt() != null) return o.getShippedAt().toLocalDate();
        return o.getCreatedAt().toLocalDate();
    }

    public static boolean byClient(Order o) {
        return o.getSource() == OrderSource.SITE || o.getSource() == OrderSource.WHATSAPP;
    }

    /** Метка источника: «сайт» (и WhatsApp — тоже покупатель сам) или «менеджер». */
    public static String srcTag(Order o) {
        return o.getSource() == OrderSource.WHATSAPP ? "WhatsApp" : byClient(o) ? "сайт" : "менеджер";
    }

    public static String srcLong(Order o) {
        return o.getSource() == OrderSource.WHATSAPP ? "через WhatsApp" : byClient(o) ? "через сайт" : "через менеджера";
    }

    /**
     * Итоги заказа. Коробки по каждой строке с округлением вверх — как в истории заказов у покупателя.
     * ret / retPcs — закреплённые возвраты (сумма и штуки); net — сумма минус возврат, pay — к оплате (не меньше нуля:
     * если возврат больше заказа, разницу возвращают клиенту). volume — объём позиций, у которых он известен;
     * noVolume — товары без коробки или её объёма (тогда volumeKnown = false и настоящий объём больше).
     */
    public record Totals(int positions, BigDecimal packs, int boxes, boolean boxesKnown, BigDecimal kg,
                         BigDecimal volume, boolean volumeKnown, BigDecimal sum, BigDecimal ret, BigDecimal retPcs,
                         List<Product> noVolume) {
        public BigDecimal net() {
            return sum.subtract(ret);
        }

        public BigDecimal pay() {
            return net().max(BigDecimal.ZERO);
        }
    }

    public static Totals totals(Order o) {
        BigDecimal retPcs = BigDecimal.ZERO;
        for (ProductReturn r : o.getReturns()) for (ProductReturnItem i : r.getItems()) retPcs = retPcs.add(i.getQuantity());
        return totals(o.getItems().stream().map(i -> new Object[]{i.getProduct(), i.getQuantity(), i.getPrice()}).toList(),
                o.getReturnTotal(), retPcs);
    }

    /** rows: [Product, quantity, price]. */
    public static Totals totals(List<Object[]> rows, BigDecimal ret) {
        return totals(rows, ret, BigDecimal.ZERO);
    }

    public static Totals totals(List<Object[]> rows, BigDecimal ret, BigDecimal retPcs) {
        BigDecimal packs = BigDecimal.ZERO, grams = BigDecimal.ZERO, vol = BigDecimal.ZERO, sum = BigDecimal.ZERO;
        int boxes = 0;
        boolean boxesKnown = true, volKnown = true;
        List<Product> noVolume = new ArrayList<>();
        for (Object[] r : rows) {
            Product p = (Product) r[0];
            BigDecimal q = (BigDecimal) r[1];
            BigDecimal price = (BigDecimal) r[2];
            packs = packs.add(q);
            sum = sum.add(price.multiply(q));
            BigDecimal units = boxUnits(p);
            if (units != null) {
                int b = q.divide(units, 0, RoundingMode.CEILING).intValue();
                boxes += b;
                if (p.getBoxVolumeM3() != null && p.getBoxVolumeM3().signum() > 0) {
                    vol = vol.add(p.getBoxVolumeM3().multiply(BigDecimal.valueOf(b)));
                } else {
                    volKnown = false;
                    if (!noVolume.contains(p)) noVolume.add(p);
                }
            } else {
                boxesKnown = false;
                volKnown = false;
                if (!noVolume.contains(p)) noVolume.add(p);
            }
            BigDecimal g = grams(p);
            if (g != null) grams = grams.add(g.multiply(q));
        }
        return new Totals(rows.size(), packs, boxes, boxesKnown, grams.movePointLeft(3), vol, volKnown && !rows.isEmpty(),
                sum, ret == null ? BigDecimal.ZERO : ret, retPcs == null ? BigDecimal.ZERO : retPcs, noVolume);
    }

    /** Оплата в списке и карточке: способ, статус, красным ли. Как payInfo в макете. */
    public record Pay(String method, String methodLong, String status, boolean bad) {
    }

    public static Pay pay(Order o) {
        PaymentMethod m = o.getPaymentMethod();
        String shortName = m == null ? "Не выбран" : m.getShortLabel();
        String longName = m == null ? "Не выбран" : m.getLabel();
        String b = o.getStatus().badge();
        if (b.equals("off")) return new Pay(shortName, longName, "—", false);
        if (o.isPaid()) return new Pay(shortName, longName, "оплачен", false);
        boolean late = b.equals("ready") || b.equals("done") || b.equals("noshow");
        if (m == PaymentMethod.CASH) {
            return new Pay(shortName, longName, b.equals("done") ? "не оплачен" : "при выдаче", b.equals("done"));
        }
        if (m == PaymentMethod.KASPI) {
            return new Pay(shortName, longName, b.equals("sent") ? "после подтверждения" : "не оплачен", !b.equals("sent"));
        }
        return new Pay(shortName, longName, "не оплачен", late);
    }

    /**
     * Неоплаченный счёт: заказ выдан (или не забран) и не оплачен, либо оплата по счёту, а денег ещё нет.
     * Новые с сайта (до подтверждения) и отменённые не считаются.
     */
    public static boolean unpaid(Order o) {
        OrderStatus s = o.getStatus();
        if (s == OrderStatus.CANCELLED || s == OrderStatus.PLACED || o.isPaid()) return false;
        String b = s.badge();
        return b.equals("done") || b.equals("noshow") || o.getPaymentMethod() == PaymentMethod.INVOICE;
    }

    /** Когда последний раз перешёл в этот статус — по журналу. */
    public static LocalDateTime lastAt(Order o, OrderStatus s) {
        LocalDateTime at = null;
        for (OrderEvent e : o.getEvents()) {
            if (e.getType() == OrderEventType.STATUS && e.getStatus() == s) at = e.getAt();
        }
        return at;
    }

    public static OrderEvent last(Order o, OrderStatus s) {
        OrderEvent last = null;
        for (OrderEvent e : o.getEvents()) {
            if (e.getType() == OrderEventType.STATUS && e.getStatus() == s) last = e;
        }
        return last;
    }

    /** Когда выдан: отгрузка, иначе по журналу. */
    public static LocalDateTime doneAt(Order o) {
        if (o.getShippedAt() != null) return o.getShippedAt();
        LocalDateTime at = lastAt(o, OrderStatus.DELIVERED);
        return at != null ? at : lastAt(o, OrderStatus.COMPLETED);
    }

    /** Отметка о действии сотрудников: когда и кто (кто может быть неизвестен у старых заказов). */
    public record Mark(LocalDateTime at, String by) {
    }

    private static Mark mark(Order o, OrderStatus s, LocalDateTime field, String fieldBy) {
        OrderEvent e = last(o, s);
        if (field != null) return new Mark(field, fieldBy != null ? fieldBy : e != null ? e.getByName() : null);
        return e == null ? null : new Mark(e.getAt(), e.getByName());
    }

    /** Принят: время принятия (у заказа сотрудника — время создания). */
    public static Mark accepted(Order o) {
        if (!byClient(o)) {
            return new Mark(o.getAcceptedAt() != null ? o.getAcceptedAt() : o.getCreatedAt(),
                    o.isImportedFromOneC() ? "1С" : null);
        }
        return mark(o, OrderStatus.CONFIRMED, o.getAcceptedAt(), null);
    }

    /** Собран: склад ГП отметил «Готов к выдаче». */
    public static Mark collected(Order o) {
        return mark(o, OrderStatus.READY, o.getReadyAt(), null);
    }

    /** Отгружен (выдан). */
    public static Mark shipped(Order o) {
        return mark(o, OrderStatus.DELIVERED, o.getShippedAt(), o.getShippedByName());
    }

    /** Завершён (выдан и оплачен); у заказов без журнала — время отметки оплаты. */
    public static Mark completed(Order o) {
        Mark m = mark(o, OrderStatus.COMPLETED, null, null);
        if (m == null && o.getStatus() == OrderStatus.COMPLETED && o.getPaidAt() != null) {
            return new Mark(o.getPaidAt(), o.getPaidByName());
        }
        return m;
    }

    /** Строка истории: плашка (класс точки), значок, подпись, время, кто, заметка. */
    public record Hist(String cls, String icon, String label, LocalDateTime at, String by, String note) {
    }

    /**
     * История заказа: журнал (OrderEvent), а то, что было до появления журнала, — по отметкам
     * времени заказа (оформлен, принят, собран, отгружен).
     */
    public static List<Hist> history(Order o) {
        List<Hist> out = new ArrayList<>();
        LocalDateTime firstEvent = o.getEvents().isEmpty() ? null : o.getEvents().get(0).getAt();
        List<Hist> synth = new ArrayList<>();
        boolean client = byClient(o);
        String customer = o.getSource() == OrderSource.WHATSAPP ? "Покупатель в WhatsApp" : "Покупатель на сайте";
        if (client) {
            synth.add(st(OrderStatus.PLACED, o.getCreatedAt(), customer, null));
            if (o.getAcceptedAt() != null) synth.add(st(OrderStatus.CONFIRMED, o.getAcceptedAt(), "Сотрудник", null));
        } else {
            synth.add(st(OrderStatus.CONFIRMED, o.getCreatedAt(), o.isImportedFromOneC() ? "1С" : "Менеджер", null));
        }
        if (o.getReadyAt() != null) synth.add(st(OrderStatus.READY, o.getReadyAt(), "Склад", null));
        if (o.getShippedAt() != null) {
            synth.add(st(OrderStatus.DELIVERED, o.getShippedAt(), o.getShippedByName() != null ? o.getShippedByName() : "Склад",
                    o.getVehicleNumber() != null ? "Гос. номер ТС: " + o.getVehicleNumber() : null));
        }
        for (Hist h : synth) {
            if (h.at() != null && (firstEvent == null || h.at().isBefore(firstEvent.minusSeconds(1)))) out.add(h);
        }
        for (OrderEvent e : o.getEvents()) {
            if (e.getType() == OrderEventType.STATUS && e.getStatus() != null) {
                out.add(st(e.getStatus(), e.getAt(), e.getByName(), e.getNote()));
            } else {
                String icon = switch (e.getType()) {
                    case MOVE -> "clock";
                    case PAY -> "cash";
                    case RETURN, RETURN_EDIT, RETURN_DELETE -> "returns";
                    default -> "s-new";
                };
                out.add(new Hist("st--new", icon, e.getType().getLabel(), e.getAt(), e.getByName(), e.getNote()));
            }
        }
        if (o.getEvents().isEmpty()) {
            if (o.getStatus() == OrderStatus.COMPLETED) out.add(st(OrderStatus.COMPLETED, null, null, "Заказ закрыт"));
            if (o.getStatus() == OrderStatus.CANCELLED) out.add(st(OrderStatus.CANCELLED, null, null, null));
        }
        return out;
    }

    private static Hist st(OrderStatus s, LocalDateTime at, String by, String note) {
        String label = s == OrderStatus.COMPLETED ? "Выдан и оплачен" : s == OrderStatus.DELIVERED ? "Выдан" : s.badgeLabel();
        return new Hist("st--" + s.badge(), "s-" + s.badge(), label, at, by, note);
    }

    /** Строка заказа в списке и в блоке «Выдача» дашборда. */
    public record Row(Order order, String badge, String badgeLabel, String src, boolean site, Totals totals, Pay pay,
                      LocalDate day) {
        public Long id() {
            return order.getId();
        }

        public Client client() {
            return order.getClient();
        }
    }

    public static Row row(Order o) {
        return new Row(o, o.getStatus().badge(), o.getStatus().badgeLabel(), srcTag(o), byClient(o), totals(o), pay(o), day(o));
    }
}
