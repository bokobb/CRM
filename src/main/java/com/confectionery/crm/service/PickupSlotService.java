package com.confectionery.crm.service;

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
 * Окна выдачи — пока заглушка без отдельной таблицы: часы работы, длина окна (1 час) и сколько
 * заказов в окне берутся из настроек crm.pickup.* (application.properties). Заказ хранит день
 * (Order.dueDate) и час начала окна (Order.pickupHour). Занятость считается по заказам дня,
 * кроме отменённых. Позже окна переедут в свою таблицу и в «Настройки».
 */
@Service
@RequiredArgsConstructor
public class PickupSlotService {

    private final OrderRepository orderRepository;

    @Value("${crm.pickup.first-hour:8}")
    private int firstHour;

    @Value("${crm.pickup.last-hour:18}")
    private int lastHour;

    @Value("${crm.pickup.capacity:3}")
    private int capacity;

    /** Сколько дней вперёд можно выбрать (сегодня + 6). */
    public static final int DAYS = 7;

    public int firstHour() {
        return firstHour;
    }

    public int lastHour() {
        return lastHour;
    }

    public int capacity() {
        return capacity;
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
        Map<Integer, Integer> taken = new HashMap<>();
        for (Object[] row : orderRepository.countBySlot(date, exceptOrderId)) {
            taken.put(((Number) row[0]).intValue(), ((Number) row[1]).intValue());
        }
        LocalDateTime now = LocalDateTime.now();
        boolean today = date.equals(now.toLocalDate());
        List<Slot> slots = new ArrayList<>();
        for (int h = firstHour; h < lastHour; h++) {
            boolean past = date.isBefore(now.toLocalDate()) || (today && h + 1 <= now.getHour());
            boolean isNow = today && h == now.getHour();
            slots.add(new Slot(h, label(h), taken.getOrDefault(h, 0), capacity, past, isNow));
        }
        return new Day(date, slots);
    }

    public List<Day> week(Long exceptOrderId) {
        List<Day> days = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int i = 0; i < DAYS; i++) {
            days.add(day(today.plusDays(i), exceptOrderId));
        }
        return days;
    }

    /**
     * Проверка окна при создании, изменении и переносе заказа: окно в часах работы, не прошло и
     * в нём есть место. Блокировки нет — окна пока заглушка; при одновременном выборе последнего
     * места оба заказа пройдут (будет 4 из 3), это видно на дашборде.
     */
    public void check(LocalDate date, Integer hour, Long exceptOrderId) {
        if (date == null || hour == null) {
            throw new IllegalArgumentException("Выберите день и окно выдачи.");
        }
        if (hour < firstHour || hour >= lastHour) {
            throw new IllegalArgumentException("Такого окна выдачи нет.");
        }
        LocalDateTime now = LocalDateTime.now();
        if (date.isBefore(now.toLocalDate()) || (date.equals(now.toLocalDate()) && hour + 1 <= now.getHour())) {
            throw new IllegalArgumentException("Окно " + label(hour) + " уже прошло — выберите другое.");
        }
        if (date.isAfter(now.toLocalDate().plusDays(DAYS - 1))) {
            throw new IllegalArgumentException("Выдачу можно назначить не дальше чем на неделю вперёд.");
        }
        if (orderRepository.countInSlot(date, hour, exceptOrderId) >= capacity) {
            throw new IllegalArgumentException("Окно " + label(hour) + " уже занято — выберите другое.");
        }
    }
}
