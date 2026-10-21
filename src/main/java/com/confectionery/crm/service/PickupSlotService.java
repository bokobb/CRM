package com.confectionery.crm.service;

import com.confectionery.crm.domain.CompanyProfile;
import com.confectionery.crm.repository.CompanyProfileRepository;
import com.confectionery.crm.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Окна выдачи: часы (окно — 1 час), сколько заказов в окне и дни выдачи задаются в админке
 * (Настройки › Окна выдачи, поля CompanyProfile.pickup*); пока их не сохраняли — из crm.pickup.*
 * (application.properties), каждый день. Заказ хранит день (Order.dueDate) и час начала окна
 * (Order.pickupHour). Занятость считается по заказам дня, кроме отменённых. В выключенный день
 * окон нет — ни в форме заказа, ни в магазине.
 */
@Service
@RequiredArgsConstructor
public class PickupSlotService {

    private final OrderRepository orderRepository;
    private final CompanyProfileRepository profileRepository;

    @Value("${crm.pickup.first-hour:8}")
    private int firstHour;

    @Value("${crm.pickup.last-hour:18}")
    private int lastHour;

    @Value("${crm.pickup.capacity:3}")
    private int capacity;

    /** Сколько дней вперёд можно выбрать (сегодня + 6). */
    public static final int DAYS = 7;

    /** Настройки окон на сейчас: из «Настроек», а чего там нет — из crm.pickup.*. */
    public record Settings(int firstHour, int lastHour, int capacity, String days) {
        public boolean open(LocalDate d) {
            return days == null || days.length() != 7 || days.charAt(d.getDayOfWeek().getValue() - 1) == '1';
        }

        public int windows() {
            return Math.max(0, lastHour - firstHour);
        }
    }

    public Settings settings() {
        CompanyProfile p = profileRepository.findById(1L).orElse(null);
        if (p == null) return new Settings(firstHour, lastHour, capacity, null);
        return new Settings(p.getPickupFirstHour() != null ? p.getPickupFirstHour() : firstHour,
                p.getPickupLastHour() != null ? p.getPickupLastHour() : lastHour,
                p.getPickupCapacity() != null ? p.getPickupCapacity() : capacity, p.getPickupDays());
    }

    public int firstHour() {
        return settings().firstHour();
    }

    public int lastHour() {
        return settings().lastHour();
    }

    public int capacity() {
        return settings().capacity();
    }

    /** Выдаём ли в этот день (день недели включён в «Окнах выдачи»). */
    public boolean open(LocalDate date) {
        return settings().open(date);
    }

    public record Slot(int hour, String label, int taken, int capacity, boolean past, boolean now) {
        public int left() {
            return Math.max(0, capacity - taken);
        }

        public boolean full() {
            return taken >= capacity;
        }
    }

    public record Day(LocalDate date, List<Slot> slots) {
        public int free() {
            return slots.stream().filter(s -> !s.past()).mapToInt(Slot::left).sum();
        }
    }

    /** «10:00–11:00» с неразрывными связками вокруг тире. */
    public static String label(int hour) {
        return String.format("%02d:00⁠–⁠%02d:00", hour, hour + 1);
    }

    public Day day(LocalDate date, Long exceptOrderId) {
        return day(date, exceptOrderId, settings());
    }

    private Day day(LocalDate date, Long exceptOrderId, Settings st) {
        if (!st.open(date)) return new Day(date, List.of());
        Map<Integer, Integer> taken = new HashMap<>();
        for (Object[] row : orderRepository.countBySlot(date, exceptOrderId)) {
            taken.put(((Number) row[0]).intValue(), ((Number) row[1]).intValue());
        }
        LocalDateTime now = LocalDateTime.now();
        boolean today = date.equals(now.toLocalDate());
        List<Slot> slots = new ArrayList<>();
        for (int h = st.firstHour(); h < st.lastHour(); h++) {
            boolean past = date.isBefore(now.toLocalDate()) || (today && h + 1 <= now.getHour());
            boolean isNow = today && h == now.getHour();
            slots.add(new Slot(h, label(h), taken.getOrDefault(h, 0), st.capacity(), past, isNow));
        }
        return new Day(date, slots);
    }

    /** Дни выдачи на неделю вперёд (сегодня + 6), выключенные дни пропускаются. */
    public List<Day> week(Long exceptOrderId) {
        Settings st = settings();
        List<Day> days = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int i = 0; i < DAYS; i++) {
            LocalDate d = today.plusDays(i);
            if (st.open(d)) days.add(day(d, exceptOrderId, st));
        }
        return days;
    }

    /**
     * Проверка окна при создании, изменении и переносе заказа: окно в часах работы, не прошло и
     * в нём есть место, день выдачи включён. Блокировки нет: при одновременном выборе последнего
     * места оба заказа пройдут (будет 4 из 3), это видно на дашборде.
     */
    public void check(LocalDate date, Integer hour, Long exceptOrderId) {
        if (date == null || hour == null) {
            throw new IllegalArgumentException("Выберите день и окно выдачи.");
        }
        Settings st = settings();
        if (!st.open(date)) {
            throw new IllegalArgumentException("В этот день выдачи нет — выберите другой.");
        }
        if (hour < st.firstHour() || hour >= st.lastHour()) {
            throw new IllegalArgumentException("Такого окна выдачи нет.");
        }
        LocalDateTime now = LocalDateTime.now();
        if (date.isBefore(now.toLocalDate()) || (date.equals(now.toLocalDate()) && hour + 1 <= now.getHour())) {
            throw new IllegalArgumentException("Окно " + label(hour) + " уже прошло — выберите другое.");
        }
        if (date.isAfter(now.toLocalDate().plusDays(DAYS - 1))) {
            throw new IllegalArgumentException("Выдачу можно назначить не дальше чем на неделю вперёд.");
        }
        if (orderRepository.countInSlot(date, hour, exceptOrderId) >= st.capacity()) {
            throw new IllegalArgumentException("Окно " + label(hour) + " уже занято — выберите другое.");
        }
    }
}
