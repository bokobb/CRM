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
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    List<Order> findByStatusOrderByCreatedAtDesc(OrderStatus status);

    /** Уже импортирован ли этот документ 1С как заказ — дедупликация в OneCOrderImportService. */
    Optional<Order> findByOneCDocumentKey(String oneCDocumentKey);

    /** Отгруженные импортированные из 1С заказы с ошибкой выгрузки — для автоповтора (OneCOrderSyncService). */
    @Query("""
            select o from Order o where o.importedFromOneC = true and o.oneCSyncError is not null
              and o.oneCDocumentKey is not null and o.shippedAt is not null and o.shippedAt >= :since""")
    List<Order> findImportedWithSyncError(@Param("since") LocalDateTime since);

    /** Не отменённые заказы с ошибкой выгрузки в 1С за последние дни — для автоповтора, когда 1С была недоступна. */
    @Query("""
            select o from Order o where o.oneCSyncError is not null
              and o.status <> com.confectionery.crm.domain.OrderStatus.CANCELLED
              and coalesce(o.shippedAt, o.createdAt) >= :since""")
    List<Order> findWithSyncErrorSince(@Param("since") LocalDateTime since);

    List<Order> findAllByOrderByCreatedAtDesc();

    List<Order> findByClientIdOrderByCreatedAtDesc(Long clientId);

    long countByStatus(OrderStatus status);

    long countByStatusIn(java.util.Collection<OrderStatus> statuses);

    long countByStatusInAndCreatedAtBetween(java.util.Collection<OrderStatus> statuses, LocalDateTime start, LocalDateTime end);

    /** Есть ли хоть один заказ (любого статуса, любого клиента) с этим товаром — см. ProductService.delete. */
    boolean existsByItems_Product_Id(Long productId);

    /**
     * Список заказов для фильтра по дате в приложении. Незавершённые заказы (статус не из
     * finalStatuses) показываются ВСЕГДА, независимо от диапазона дат — иначе заказ, собранный
     * или отгруженный несколько дней назад, но ещё не закрытый, пропадает из вида при дефолтном
     * фильтре "сегодня" (см. OrderService). Отгруженный заказ относится ко дню ОТГРУЗКИ, а не
     * создания — тот же день, в который его сумма идёт в продажи на дашборде (sumSalesBetween) и
     * датируется реализация в 1С. Активные заказы идут первыми, затем по этой дате.
     */
    @Query("SELECT o FROM Order o WHERE (COALESCE(o.shippedAt, o.createdAt) BETWEEN :start AND :end " +
            "OR o.status NOT IN :finalStatuses) " +
            "AND (:status IS NULL OR o.status = :status) " +
            "ORDER BY CASE WHEN o.status NOT IN :finalStatuses THEN 0 ELSE 1 END, COALESCE(o.shippedAt, o.createdAt) DESC")
    List<Order> findForDateRange(@Param("status") OrderStatus status,
                                  @Param("start") LocalDateTime start,
                                  @Param("end") LocalDateTime end,
                                  @Param("finalStatuses") List<OrderStatus> finalStatuses);

    List<Order> findByCreatedAtBetween(LocalDateTime start, LocalDateTime end);

    List<Order> findByShippedAtBetween(LocalDateTime start, LocalDateTime end);

    List<Order> findByStatusInAndCreatedAtBefore(java.util.Collection<OrderStatus> statuses, LocalDateTime before);

    long countByStatusInAndShippedAtBetween(java.util.Collection<OrderStatus> statuses, LocalDateTime start, LocalDateTime end);

    List<Order> findByStatusInOrderByCreatedAtAsc(java.util.Collection<OrderStatus> statuses);

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

    /** Неоплаченные долги (для раздела "Долги") — сначала ближайший срок оплаты; отменённые заказы не в счёт. */
    @Query("SELECT o FROM Order o WHERE o.debtDueDate IS NOT NULL AND (o.debtPaid IS NULL OR o.debtPaid = false) " +
            "AND o.status <> com.confectionery.crm.domain.OrderStatus.CANCELLED " +
            "ORDER BY o.debtDueDate ASC")
    List<Order> findUnpaidDebts();

    /** Долги, у которых срок оплаты уже прошёл — для фоновой проверки (ScheduledJobs). */
    @Query("SELECT o FROM Order o WHERE o.debtDueDate IS NOT NULL AND o.debtDueDate < :today " +
            "AND (o.debtPaid IS NULL OR o.debtPaid = false) " +
            "AND o.status <> com.confectionery.crm.domain.OrderStatus.CANCELLED")
    List<Order> findOverdueDebts(@Param("today") LocalDate today);

    /** Занятые места в окне выдачи (см. PickupSlotService): заказы дня с этим часом, кроме отменённых и exceptId. */
    @Query("select count(o) from Order o where o.dueDate = :day and o.pickupHour = :hour " +
            "and o.status <> com.confectionery.crm.domain.OrderStatus.CANCELLED and (:exceptId is null or o.id <> :exceptId)")
    long countInSlot(@Param("day") LocalDate day, @Param("hour") Integer hour, @Param("exceptId") Long exceptId);

    /** Сколько заказов в каждом окне дня: [час, число] — для выбора окна. */
    @Query("select o.pickupHour, count(o) from Order o where o.dueDate = :day and o.pickupHour is not null " +
            "and o.status <> com.confectionery.crm.domain.OrderStatus.CANCELLED and (:exceptId is null or o.id <> :exceptId) " +
            "group by o.pickupHour")
    List<Object[]> countBySlot(@Param("day") LocalDate day, @Param("exceptId") Long exceptId);

    /**
     * Заказы с выдачей в этот день, кроме отменённых — «Выдача сегодня» и блок «Выдача» на дашборде.
     * День выдачи — dueDate, у заказов без него (1С, старые) — день отгрузки, а до неё — оформления
     * (как AdminOrders.day).
     */
    @Query("select distinct o from Order o join fetch o.client where o.status <> com.confectionery.crm.domain.OrderStatus.CANCELLED " +
            "and (o.dueDate = :day or (o.dueDate is null and ((o.shippedAt is not null and o.shippedAt >= :start and o.shippedAt < :end) " +
            "or (o.shippedAt is null and o.createdAt >= :start and o.createdAt < :end))))")
    List<Order> findForPickupDay(@Param("day") LocalDate day, @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /**
     * Заказы, у которых в этот день было действие: оформлен (с вечера прошлого дня — заказы после 18:00
     * переходят на утро), принят, собран, отгружен, оплачен или есть запись в журнале. Для окон «Выдачи» на дашборде.
     */
    @Query("select distinct o from Order o join fetch o.client left join o.events e where o.status <> com.confectionery.crm.domain.OrderStatus.CANCELLED " +
            "and ((o.createdAt >= :from and o.createdAt < :end) or (o.acceptedAt >= :start and o.acceptedAt < :end) " +
            "or (o.readyAt >= :start and o.readyAt < :end) or (o.shippedAt >= :start and o.shippedAt < :end) " +
            "or (o.paidAt >= :start and o.paidAt < :end) or (e.at >= :start and e.at < :end))")
    List<Order> findWithActivity(@Param("from") LocalDateTime from, @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /** Выручка по выданным заказам: [id, shippedAt, сумма позиций] — для графика «Выручка» дашборда. */
    @Query("select o.id, o.shippedAt, sum(i.price * i.quantity) from Order o join o.items i " +
            "where o.status in (com.confectionery.crm.domain.OrderStatus.DELIVERED, com.confectionery.crm.domain.OrderStatus.COMPLETED) " +
            "and o.shippedAt >= :start and o.shippedAt < :end group by o.id, o.shippedAt")
    List<Object[]> revenueByOrder(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /** Что берут: [productId, сумма штук] по выданным заказам за период. */
    @Query("select i.product.id, sum(i.quantity) from Order o join o.items i " +
            "where o.status in (com.confectionery.crm.domain.OrderStatus.DELIVERED, com.confectionery.crm.domain.OrderStatus.COMPLETED) " +
            "and o.shippedAt >= :start and o.shippedAt < :end group by i.product.id")
    List<Object[]> soldByProduct(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /** Возвраты, «доля от отгруженного»: [clientId, productId, сумма штук] по выданным заказам за период. */
    @Query("select o.client.id, i.product.id, sum(i.quantity) from Order o join o.items i " +
            "where o.status in (com.confectionery.crm.domain.OrderStatus.DELIVERED, com.confectionery.crm.domain.OrderStatus.COMPLETED) " +
            "and o.shippedAt >= :start and o.shippedAt < :end group by o.client.id, i.product.id")
    List<Object[]> soldByClientProduct(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    List<Order> findByStatusIn(java.util.Collection<OrderStatus> statuses);

    /** Ждут фоновой отправки в 1С (см. OneCQueue). */
    @Query("select e.id from Order e where e.oneCPending = true order by e.id")
    List<Long> findOneCPendingIds();

    @org.springframework.data.jpa.repository.Modifying
    @Query("update Order e set e.oneCPending = false where e.id = :id")
    int clearOneCPending(@Param("id") Long id);

    /** Сколько документов не ушло в 1С (есть ошибка выгрузки) — «Настройки › 1С». */
    long countByOneCSyncErrorIsNotNull();
}
