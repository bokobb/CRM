package com.confectionery.crm.repository;

import com.confectionery.crm.domain.Purchase;
import com.confectionery.crm.domain.PurchaseItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PurchaseRepository extends JpaRepository<Purchase, Long> {

    List<Purchase> findAllByOrderByCreatedAtDesc();

    List<Purchase> findByPurchaseDateBetweenOrderByCreatedAtDesc(LocalDate from, LocalDate to);

    boolean existsByCounterpartyId(Long counterpartyId);

    /** Уже импортирован ли этот документ 1С как закупка — дедупликация в OneCPurchaseImportService. */
    Optional<Purchase> findByOneCDocumentKey(String oneCDocumentKey);

    /**
     * Строки закупок с ценой по этим видам сырья, самые свежие первыми — первая строка по
     * каждому сырью даёт его последнюю закупочную цену (себестоимость в техкарте, TechCardService).
     */
    @Query("""
            select pi from PurchaseItem pi join fetch pi.purchase p
            where pi.ingredient.id in :ingredientIds and pi.totalPrice is not null and pi.quantity > 0
            order by p.purchaseDate desc, p.createdAt desc
            """)
    List<PurchaseItem> findPricedItemsNewestFirst(@Param("ingredientIds") Collection<Long> ingredientIds);
}
