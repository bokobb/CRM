package com.confectionery.crm.service;

import com.confectionery.crm.domain.Ingredient;
import com.confectionery.crm.domain.Order;
import com.confectionery.crm.domain.ProductionRequest;
import com.confectionery.crm.domain.Role;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

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
    private final OrderService orderService;
    private final NotificationService notificationService;
    private final ProductionRequestService productionRequestService;

    /**
     * Автозавершение заявок на сырьё: склад забыл «Завершить» — CRM нажимает его сама в 20:00
     * следующего дня (см. ProductionRequestService.autoCompleteAt): требование-накладная
     * проводится в 1С обычной очередью, в истории заявки — «Автозавершение». Раз в час, а не
     * только в 20:00, — чтобы после перезапуска сервера догнать пропущенное.
     */
    @Scheduled(cron = "${crm.requests.auto-complete-cron:0 0 * * * *}", zone = "${crm.schedule-zone:GMT+05:00}")
    public void autoCompleteRequests() {
        for (Long id : productionRequestService.dueForAutoComplete(LocalDateTime.now())) {
            try {
                ProductionRequest request = productionRequestService.completeRequisition(id, ProductionRequestService.AUTO_COMPLETE_BY);
                log.info("Заявка №{} «{}» завершена автоматически — склад не нажал «Завершить»", id, request.getTitle());
            } catch (Exception e) {
                log.warn("Автозавершение заявки №{} не прошло: {}", id, e.getMessage());
            }
        }
    }

    @Scheduled(cron = "${crm.stock.low-stock-check-cron}", zone = "${crm.schedule-zone:GMT+05:00}")
    public void checkLowStock() {
        for (Ingredient ingredient : ingredientService.findLowStock()) {
            String prefix = "Низкий остаток: " + ingredient.getName();
            if (!notificationService.wasNotifiedRecently("склад", prefix)) {
                notificationService.notify("склад", prefix + " — остаток " + ingredient.getQuantityInStock()
                        + " " + ingredient.getUnit() + ", минимум " + ingredient.getMinThreshold() + " " + ingredient.getUnit(), null);
            }
        }
    }

    /**
     * Просроченные долги: у нас нет доступа к настоящему SMS-шлюзу (платный внешний сервис,
     * нужны его API-ключи), поэтому "SMS бухгалтеру" реализовано как push/лог-уведомление роли
     * ACCOUNTANT — тем же каналом, что и остальные уведомления в приложении.
     */
    @Scheduled(cron = "${crm.debts.overdue-check-cron}", zone = "${crm.schedule-zone:GMT+05:00}")
    public void checkOverdueDebts() {
        for (Order order : orderService.findOverdueDebts()) {
            String prefix = "Просрочен долг по заказу №" + order.getId();
            if (!notificationService.wasNotifiedRecently("role:" + Role.ACCOUNTANT.name(), prefix)) {
                notificationService.notifyRole(Role.ACCOUNTANT,
                        prefix + " (" + order.getClient().getName() + ") — срок оплаты был " + order.getDebtDueDate(),
                        order);
            }
        }
    }
}
