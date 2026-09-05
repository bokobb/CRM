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

    /**
     * Ещё не выгруженные в 1С РУЧНЫЕ поступления товара за период (см. OneCReceiptSyncService) —
     * только manual=true. Приход от заявок на производство сюда не попадает — для него в 1С
     * есть свой правильный документ ("Отчёт производства за смену" + "Требование-накладная",
     * см. OneCProductionSyncService), "Оприходование товаров" для него не подходит.
     */
    List<StockMovement> findByTypeAndManualAndProductIsNotNullAndOneCSyncedAtIsNullAndCreatedAtBetween(
            StockMovementType type, boolean manual, LocalDateTime from, LocalDateTime to);
}
