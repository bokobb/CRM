package com.confectionery.crm.repository;

import com.confectionery.crm.domain.ProductReturn;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ProductReturnRepository extends JpaRepository<ProductReturn, Long> {

    List<ProductReturn> findByReturnDateBetweenOrderByCreatedAtDesc(LocalDate from, LocalDate to);

    List<ProductReturn> findAllByOrderByCreatedAtDesc();

    List<ProductReturn> findByClientIdOrderByCreatedAtDesc(Long clientId);

    /** Возвраты клиента, ещё не закреплённые ни за одним заказом — выбор в карточке заказа. */
    List<ProductReturn> findByClientIdAndOrderIsNullOrderByCreatedAtDesc(Long clientId);

    List<ProductReturn> findByOrderIdOrderByCreatedAtDesc(Long orderId);

    /**
     * Сумма возвратов, вычитаемая из продаж на дашборде: только закреплённые за заказом
     * (незакреплённый возврат нейтрален) и по той же дате, по которой засчитана сама продажа —
     * дате отгрузки заказа (см. OrderRepository.sumSalesBetween), а не дате оформления
     * возврата: возврат по вчерашнему заказу уменьшает вчерашнюю продажу.
     */
    @Query("SELECT COALESCE(SUM(i.price * i.quantity), 0) FROM ProductReturn r JOIN r.items i JOIN r.order o " +
            "WHERE o.status IN (com.confectionery.crm.domain.OrderStatus.DELIVERED, com.confectionery.crm.domain.OrderStatus.COMPLETED) " +
            "AND o.shippedAt BETWEEN :start AND :end")
    BigDecimal sumForSalesBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /**
     * Возвраты по выданным заказам: [id заказа, сумма возврата] — выручка дашборда за вычетом возвратов, по дню
     * выдачи заказа (как sumForSalesBetween, только по заказам).
     */
    @Query("select o.id, sum(i.price * i.quantity) from ProductReturn r join r.items i join r.order o " +
            "where o.status in (com.confectionery.crm.domain.OrderStatus.DELIVERED, com.confectionery.crm.domain.OrderStatus.COMPLETED) " +
            "and o.shippedAt >= :start and o.shippedAt < :end group by o.id")
    List<Object[]> sumByOrderShippedBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /** Возвраты по дням: [день возврата, штук, сумма] — «Возвраты» в выручке дашборда. */
    @Query("select r.returnDate, sum(i.quantity), sum(i.price * i.quantity) from ProductReturn r join r.items i " +
            "where r.returnDate between :from and :to group by r.returnDate")
    List<Object[]> sumByDayBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);

    boolean existsByItems_Product_Id(Long productId);

    /** Документ 1С уже есть в CRM (создан CRM или импортирован) — дедупликация в OneCReturnImportService. */
    Optional<ProductReturn> findByOneCDocumentKey(String oneCDocumentKey);

    /** Ждут фоновой отправки в 1С (см. OneCQueue). */
    @Query("select e.id from ProductReturn e where e.oneCPending = true order by e.id")
    List<Long> findOneCPendingIds();

    @org.springframework.data.jpa.repository.Modifying
    @Query("update ProductReturn e set e.oneCPending = false where e.id = :id")
    int clearOneCPending(@Param("id") Long id);

    /** Сколько документов не ушло в 1С (есть ошибка выгрузки) — «Настройки › 1С». */
    long countByOneCSyncErrorIsNotNull();
}
