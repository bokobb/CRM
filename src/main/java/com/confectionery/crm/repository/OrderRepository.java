package com.confectionery.crm.repository;

import com.confectionery.crm.domain.Order;
import com.confectionery.crm.domain.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface OrderRepository extends JpaRepository<Order, Long> {

    List<Order> findByStatusOrderByCreatedAtDesc(OrderStatus status);

    List<Order> findAllByOrderByCreatedAtDesc();

    List<Order> findByClientIdOrderByCreatedAtDesc(Long clientId);

    long countByStatus(OrderStatus status);

    /** Есть ли хоть один заказ (любого статуса, любого клиента) с этим товаром — см. ProductService.delete. */
    boolean existsByItems_Product_Id(Long productId);

    /**
     * Список заказов для фильтра по дате в приложении. Незавершённые заказы (статус не из
     * finalStatuses) показываются ВСЕГДА, независимо от диапазона дат — иначе заказ, собранный
     * или отгруженный несколько дней назад, но ещё не закрытый, пропадает из вида при дефолтном
     * фильтре "сегодня" (см. OrderService). Активные заказы идут первыми (в начале списка),
     * затем по дате создания.
     */
    @Query("SELECT o FROM Order o WHERE (o.createdAt BETWEEN :start AND :end OR o.status NOT IN :finalStatuses) " +
            "AND (:status IS NULL OR o.status = :status) " +
            "ORDER BY CASE WHEN o.status NOT IN :finalStatuses THEN 0 ELSE 1 END, o.createdAt DESC")
    List<Order> findForDateRange(@Param("status") OrderStatus status,
                                  @Param("start") LocalDateTime start,
                                  @Param("end") LocalDateTime end,
                                  @Param("finalStatuses") List<OrderStatus> finalStatuses);

    long countByStatusAndCreatedAtBetween(OrderStatus status, LocalDateTime start, LocalDateTime end);

    /** Сумма проданного (отгружено или завершено) за период — по дате отгрузки. */
    @Query("SELECT COALESCE(SUM(oi.price * oi.quantity), 0) FROM Order o JOIN o.items oi " +
            "WHERE o.status IN (com.confectionery.crm.domain.OrderStatus.DELIVERED, com.confectionery.crm.domain.OrderStatus.COMPLETED) " +
            "AND o.shippedAt BETWEEN :start AND :end")
    BigDecimal sumSalesBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /** То же самое, но для одного клиента — используется для месячной суммы покупок. */
    @Query("SELECT COALESCE(SUM(oi.price * oi.quantity), 0) FROM Order o JOIN o.items oi " +
            "WHERE o.client.id = :clientId " +
            "AND o.status IN (com.confectionery.crm.domain.OrderStatus.DELIVERED, com.confectionery.crm.domain.OrderStatus.COMPLETED) " +
            "AND o.shippedAt BETWEEN :start AND :end")
    BigDecimal sumSalesForClientBetween(@Param("clientId") Long clientId,
                                         @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /** Неоплаченные долги (для раздела "Долги") — сначала ближайший срок оплаты. */
    @Query("SELECT o FROM Order o WHERE o.debtDueDate IS NOT NULL AND (o.debtPaid IS NULL OR o.debtPaid = false) " +
            "ORDER BY o.debtDueDate ASC")
    List<Order> findUnpaidDebts();

    /** Долги, у которых срок оплаты уже прошёл — для фоновой проверки (ScheduledJobs). */
    @Query("SELECT o FROM Order o WHERE o.debtDueDate IS NOT NULL AND o.debtDueDate < :today " +
            "AND (o.debtPaid IS NULL OR o.debtPaid = false)")
    List<Order> findOverdueDebts(@Param("today") LocalDate today);
}
