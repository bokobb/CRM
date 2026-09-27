package com.confectionery.crm.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class ProductClientVisibilityTest {

    @Test
    void visibleByDefault() {
        assertThat(new Product().isVisibleToClients()).isTrue();
    }

    @Test
    void hiddenWithoutDateStaysHidden() {
        Product product = new Product();
        product.setHiddenFromClients(true);
        assertThat(product.isVisibleToClients()).isFalse();
    }

    @Test
    void hiddenUntilDateIsInclusiveAndReturnsNextDay() {
        Product product = new Product();
        product.setHiddenFromClients(true);
        product.setHiddenFromClientsUntil(LocalDate.now());
        assertThat(product.isVisibleToClients()).isFalse();

        product.setHiddenFromClientsUntil(LocalDate.now().minusDays(1));
        assertThat(product.isVisibleToClients()).isTrue();
    }

    @Test
    void archivedProductIsNeverVisible() {
        Product product = new Product();
        product.setActive(false);
        assertThat(product.isVisibleToClients()).isFalse();
    }
}
