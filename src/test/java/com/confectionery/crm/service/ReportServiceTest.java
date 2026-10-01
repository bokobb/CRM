package com.confectionery.crm.service;

import com.confectionery.crm.api.dto.ReportDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** Все отчёты строятся (JPQL проверяется только при выполнении) — на пустой базе в памяти. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:reports;DB_CLOSE_DELAY=-1",
        "crm.onec.base-url=",
        "spring.task.scheduling.enabled=false"
})
class ReportServiceTest {

    @Autowired
    ReportService reportService;

    @Test
    void allReportsBuild() {
        LocalDate from = LocalDate.of(2026, 9, 1), to = LocalDate.of(2026, 9, 30);
        for (ReportService.Kind kind : ReportService.Kind.values()) {
            ReportDto r = reportService.build(kind, from, to, true);
            assertThat(r.columns()).isNotEmpty();
            if (r.totals() != null) assertThat(r.totals()).hasSameSizeAs(r.columns());
            r.rows().forEach(row -> assertThat(row.cells()).hasSameSizeAs(r.columns()));
            ReportDto hidden = reportService.build(kind, from, to, false);
            hidden.rows().forEach(row -> assertThat(row.cells()).hasSameSizeAs(hidden.columns()));
        }
    }
}
