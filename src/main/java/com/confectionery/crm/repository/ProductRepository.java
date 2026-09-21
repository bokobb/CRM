package com.confectionery.crm.repository;

import com.confectionery.crm.domain.Product;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {

    /** Только активные (не архивные) товары — для каталога/выбора при создании новых заказов. */
    List<Product> findByActiveTrue();

    /**
     * Товар-эталон группы для пересчёта массы в штуки в сверке заявки на сырьё (см.
     * Product.massReferenceProduct/ProductionRequestService.massSummary) — даже если сам не
     * входит в конкретную заявку. "First" — на случай, если в группе ошибочно отметили больше
     * одного (по документации поля должен быть максимум один).
     */
    Optional<Product> findFirstByGroupAndMassReferenceProductTrue(String group);

    /** Сопоставление строки документа 1С с товаром по GUID номенклатуры (см. OneCOrderImportService). */
    Optional<Product> findByOneCNomenclatureKey(String oneCNomenclatureKey);
}
