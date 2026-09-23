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

    boolean existsByItems_Product_Id(Long productId);

    /** Документ 1С уже есть в CRM (создан CRM или импортирован) — дедупликация в OneCReturnImportService. */
    Optional<ProductReturn> findByOneCDocumentKey(String oneCDocumentKey);
}
