package com.confectionery.crm.service;

import com.confectionery.crm.domain.Ingredient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Фоновая проверка остатков склада — независимо от списаний из производства
 * (сырьё могло испортиться, быть учтено вручную и т.д.). Расписание задаётся
 * свойством crm.stock.low-stock-check-cron (по умолчанию — раз в час).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduledJobs {

    private final IngredientService ingredientService;
    private final NotificationService notificationService;

    @Scheduled(cron = "${crm.stock.low-stock-check-cron}")
    public void checkLowStock() {
        for (Ingredient ingredient : ingredientService.findLowStock()) {
            String prefix = "Низкий остаток: " + ingredient.getName();
            if (!notificationService.wasNotifiedRecently("склад", prefix)) {
                notificationService.notify("склад", prefix + " — остаток " + ingredient.getQuantityInStock()
                        + " " + ingredient.getUnit() + ", минимум " + ingredient.getMinThreshold() + " " + ingredient.getUnit(), null);
            }
        }
    }
}
