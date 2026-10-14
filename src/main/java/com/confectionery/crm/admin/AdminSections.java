package com.confectionery.crm.admin;

import com.confectionery.crm.domain.Role;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static com.confectionery.crm.domain.Role.*;

/**
 * Разделы меню админки: ключ (он же адрес /admin/{key}), название, группа и роли, которым раздел
 * виден. Порядок и группы — как в макете (17 пунктов). Администратор (ADMIN) видит всё; права по
 * разделам для остальных ролей взяты из того, что эти роли делают в приложении.
 */
public final class AdminSections {

    private AdminSections() {
    }

    public record Section(String key, String title, String group, Set<Role> roles) {
        public String href() {
            return "/admin/" + key;
        }

        public boolean visibleTo(Role role) {
            return role == ADMIN || roles.contains(role);
        }
    }

    private static final Set<Role> SELLERS = EnumSet.of(SALES, ACCOUNTANT, WAREHOUSE_FG);

    public static final List<Section> ALL = List.of(
            new Section("dashboard", "Дашборд", "", SELLERS),
            new Section("orders", "Заказы", "Продажи", SELLERS),
            new Section("returns", "Возвраты", "Продажи", SELLERS),
            new Section("clients", "Клиенты", "Продажи", EnumSet.of(SALES, ACCOUNTANT)),
            new Section("production", "Производство", "Производство", EnumSet.of(TECHNOLOGIST, WAREHOUSE_RAW, ACCOUNTANT)),
            new Section("recipes", "Рецепты", "Производство", EnumSet.of(TECHNOLOGIST, WAREHOUSE_RAW)),
            new Section("techcards", "Техкарта", "Производство", EnumSet.of(TECHNOLOGIST)),
            new Section("stock", "Склад", "Склад и закупки", EnumSet.of(WAREHOUSE_FG, SALES, ACCOUNTANT)),
            new Section("materials", "Склад сырья", "Склад и закупки", EnumSet.of(WAREHOUSE_RAW, ACCOUNTANT, TECHNOLOGIST)),
            new Section("requests", "Заявки на сырьё", "Склад и закупки", EnumSet.of(WAREHOUSE_RAW, WAREHOUSE_FG, TECHNOLOGIST, ACCOUNTANT)),
            new Section("purchases", "Покупки", "Склад и закупки", EnumSet.of(WAREHOUSE_RAW, ACCOUNTANT)),
            new Section("products", "Товары", "Каталог", EnumSet.of(SALES, WAREHOUSE_FG, ACCOUNTANT)),
            new Section("photos", "Фото товаров", "Каталог", EnumSet.noneOf(Role.class)),
            new Section("staff", "Сотрудники", "Команда", EnumSet.of(TECHNOLOGIST, ACCOUNTANT)),
            new Section("jobs", "Вакансии", "Команда", EnumSet.of(ACCOUNTANT)),
            new Section("reports", "Отчёты", "|", EnumSet.of(ACCOUNTANT, TECHNOLOGIST, WAREHOUSE_FG)),
            new Section("settings", "Настройки", "|", EnumSet.noneOf(Role.class)));

    public static Section byKey(String key) {
        return ALL.stream().filter(s -> s.key().equals(key)).findFirst().orElse(null);
    }

    /** Первый доступный роли раздел — куда вести после входа. */
    public static Section home(Role role) {
        return ALL.stream().filter(s -> s.visibleTo(role)).findFirst().orElse(byKey("settings"));
    }

    /** Группа меню: подпись (пусто — без подписи, «|» — после черты) и её пункты. */
    public record Group(String title, boolean separator, List<Section> items) {
    }

    public static List<Group> menu(Role role) {
        java.util.LinkedHashMap<String, List<Section>> groups = new java.util.LinkedHashMap<>();
        for (Section s : ALL) {
            if (s.visibleTo(role)) groups.computeIfAbsent(s.group(), g -> new java.util.ArrayList<>()).add(s);
        }
        return groups.entrySet().stream()
                .map(e -> new Group("|".equals(e.getKey()) ? "" : e.getKey(), "|".equals(e.getKey()), e.getValue()))
                .toList();
    }
}
