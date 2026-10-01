package com.confectionery.crm.service;

import com.confectionery.crm.domain.Counterparty;
import com.confectionery.crm.domain.Ingredient;
import com.confectionery.crm.domain.Purchase;
import com.confectionery.crm.repository.CounterpartyRepository;
import com.confectionery.crm.repository.IngredientRepository;
import com.confectionery.crm.repository.PurchaseRepository;
import com.confectionery.crm.repository.StockMovementRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Правка закупки: старый приход откатывается, новый проводится; лишних движений не остаётся. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:purchase-update;DB_CLOSE_DELAY=-1",
        "crm.onec.base-url=",
        "spring.task.scheduling.enabled=false"
})
class PurchaseUpdateTest {

    @Autowired PurchaseService purchaseService;
    @Autowired IngredientRepository ingredientRepository;
    @Autowired CounterpartyRepository counterpartyRepository;
    @Autowired PurchaseRepository purchaseRepository;
    @Autowired StockMovementRepository stockMovementRepository;
    @Autowired TransactionTemplate tx;

    @Test
    void updateRevertsOldReceipt() {
        Ingredient flour = ingredient("Мука тест");
        Ingredient sugar = ingredient("Сахар тест");
        Counterparty a = counterparty("Поставщик А"), b = counterparty("Поставщик Б");
        long movementsBefore = stockMovementRepository.count();

        Purchase p = purchaseService.create(a.getId(),
                List.of(new PurchaseService.ItemInput(flour.getId(), new BigDecimal("10"), new BigDecimal("5000"))),
                LocalDate.of(2026, 10, 1), "test");
        assertThat(stock(flour)).isEqualByComparingTo("10");

        purchaseService.update(p.getId(), b.getId(), List.of(
                        new PurchaseService.ItemInput(flour.getId(), new BigDecimal("4"), new BigDecimal("2000")),
                        new PurchaseService.ItemInput(sugar.getId(), new BigDecimal("3"), null)),
                LocalDate.of(2026, 9, 30), "test");

        assertThat(stock(flour)).isEqualByComparingTo("4");
        assertThat(stock(sugar)).isEqualByComparingTo("3");
        assertThat(stockMovementRepository.count()).isEqualTo(movementsBefore + 2);
        Purchase saved = purchaseService.findById(p.getId());
        assertThat(saved.getCounterparty().getId()).isEqualTo(b.getId());
        assertThat(saved.getPurchaseDate()).isEqualTo(LocalDate.of(2026, 9, 30));
        Integer items = tx.execute(st -> purchaseService.findById(p.getId()).getItems().size());
        assertThat(items).isEqualTo(2);
    }

    private BigDecimal stock(Ingredient i) {
        return ingredientRepository.findById(i.getId()).orElseThrow().getQuantityInStock();
    }

    private Ingredient ingredient(String name) {
        Ingredient i = new Ingredient();
        i.setName(name);
        i.setUnit("кг");
        return ingredientRepository.save(i);
    }

    private Counterparty counterparty(String name) {
        Counterparty c = new Counterparty();
        c.setName(name);
        return counterpartyRepository.save(c);
    }
}
