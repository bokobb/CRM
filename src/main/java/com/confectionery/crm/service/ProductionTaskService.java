package com.confectionery.crm.service;

import com.confectionery.crm.domain.Order;
import com.confectionery.crm.domain.OrderStatus;
import com.confectionery.crm.domain.ProductionTask;
import com.confectionery.crm.domain.ProductionTaskStatus;
import com.confectionery.crm.repository.ProductionTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Цеховая доска задач. Завершение задачи автоматически:
 *  1) списывает сырьё со склада по рецептуре (StockService);
 *  2) если это была последняя незавершённая задача заказа — переводит заказ в READY
 *     и уведомляет клиента, что заказ готов.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductionTaskService {

    private final ProductionTaskRepository productionTaskRepository;
    private final StockService stockService;
    private final NotificationService notificationService;

    public List<ProductionTask> findAll() {
        return productionTaskRepository.findAllByOrderByCreatedAtAsc();
    }

    public List<ProductionTask> findByStatus(ProductionTaskStatus status) {
        return productionTaskRepository.findByStatusOrderByCreatedAtAsc(status);
    }

    @Transactional
    public ProductionTask start(Long taskId, String assignedTo) {
        ProductionTask task = get(taskId);
        task.setStatus(ProductionTaskStatus.IN_PROGRESS);
        if (assignedTo != null && !assignedTo.isBlank()) {
            task.setAssignedTo(assignedTo);
        }
        return task;
    }

    @Transactional
    public ProductionTask complete(Long taskId) {
        ProductionTask task = get(taskId);
        if (task.getStatus() == ProductionTaskStatus.DONE) {
            return task;
        }
        task.setStatus(ProductionTaskStatus.DONE);
        task.setCompletedAt(LocalDateTime.now());

        stockService.deductForProduction(task.getProduct(), task.getQuantity());

        Order order = task.getOrder();
        boolean allDone = order.getProductionTasks().stream()
                .allMatch(t -> t.getStatus() == ProductionTaskStatus.DONE);
        if (allDone && order.getStatus() == OrderStatus.IN_PRODUCTION) {
            order.setStatus(OrderStatus.READY);
            notificationService.notify(OrderService.recipientOf(order),
                    "Заказ #" + order.getId() + " готов к выдаче", order);
        }

        return task;
    }

    private ProductionTask get(Long id) {
        return productionTaskRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Задача не найдена: " + id));
    }
}
