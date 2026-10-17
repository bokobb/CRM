package com.confectionery.crm.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SeoServiceTest {

    @Test
    void openingHoursFromProfileText() {
        assertThat(SeoService.openingHours("Пн–Сб с 07:00 до 18:00, обед с 12:00 до 13:00")).isEqualTo("Mo-Sa 07:00-18:00");
        assertThat(SeoService.openingHours("Пн-Пт 8:30–17:00")).isEqualTo("Mo-Fr 08:30-17:00");
        assertThat(SeoService.openingHours("ежедневно")).isNull();
        assertThat(SeoService.openingHours(null)).isNull();
    }
}
