package com.confectionery.crm.repository;

import com.confectionery.crm.domain.StockMovement;
import com.confectionery.crm.domain.StockMovementType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface StockMovementRepository extends JpaRepository<StockMovement, Long> {

    boolean existsByProductId(Long productId);

    /** Все движения сырья за период (ручные и по заявкам) — журнал «Склада сырья», расход в цех. */
    List<StockMovement> findByIngredientIsNotNullAndCreatedAtBetweenOrderByCreatedAtDesc(LocalDateTime from, LocalDateTime to);

    /** Журнал склада готовой продукции в админке: оприходование заявок, документы, исправления. */
    List<StockMovement> findByProductIsNotNullAndCreatedAtBetweenOrderByCreatedAtDesc(LocalDateTime from, LocalDateTime to);

    /** Движения одного сырья с даты — карточка сырья. */
    List<StockMovement> findByIngredientIdAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(Long ingredientId, LocalDateTime from);

    /**
     * Только ручные движения (см. StockMovement.manual) — тип/период необязательны
     * (null = без ограничения по этому полю). Фильтр по названию — уже в StockService,
     * т.к. имя лежит в связанном Ingredient/Product, а не в самой строке движения.
     */
    @Query("""
            SELECT m FROM StockMovement m
            WHERE m.manual = true
              AND (:type IS NULL OR m.type = :type)
              AND (CAST(:from AS java.time.LocalDateTime) IS NULL OR m.createdAt >= :from)
              AND (CAST(:to AS java.time.LocalDateTime) IS NULL OR m.createdAt < :to)
            ORDER BY m.createdAt DESC
            """)
    List<StockMovement> findManual(@Param("type") StockMovementType type,
                                    @Param("from") LocalDateTime from,
                                    @Param("to") LocalDateTime to);
}
