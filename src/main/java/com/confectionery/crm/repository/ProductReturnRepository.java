package com.confectionery.crm.repository;

import com.confectionery.crm.domain.ProductReturn;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
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
     * Сумма возвратов за период по дате возврата — вычитается из продаж на дашборде. Только
     * закреплённые за заказом: незакреплённый возврат нейтрален, пока его не привязали.
     */
    @Query("SELECT COALESCE(SUM(i.price * i.quantity), 0) FROM ProductReturn r JOIN r.items i " +
            "WHERE r.order IS NOT NULL AND r.returnDate BETWEEN :from AND :to")
    BigDecimal sumBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);

    boolean existsByItems_Product_Id(Long productId);

    /** Документ 1С уже есть в CRM (создан CRM или импортирован) — дедупликация в OneCReturnImportService. */
    Optional<ProductReturn> findByOneCDocumentKey(String oneCDocumentKey);
}
