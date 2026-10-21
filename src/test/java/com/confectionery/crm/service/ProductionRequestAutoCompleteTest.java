package com.confectionery.crm.service;

import com.confectionery.crm.domain.ProductionRequest;
import com.confectionery.crm.domain.ProductionRequestLine;
import com.confectionery.crm.domain.ProductionRequestRevision;
import com.confectionery.crm.domain.ProductionRequestStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** Автозавершение: склад не нажал «Завершить» — заявка завершается сама в 20:00 следующего дня. */
class ProductionRequestAutoCompleteTest {

    static ProductionRequest received(LocalDate date, LocalDateTime receivedAt) {
        ProductionRequest r = new ProductionRequest();
        r.setStatus(ProductionRequestStatus.RECEIVED);
        r.setDocumentDate(date);
        ProductionRequestLine line = new ProductionRequestLine();
        line.setReceivedAt(receivedAt);
        r.getLines().add(line);
        return r;
    }

    @Test
    void nextDayAt20() {
        ProductionRequest r = received(LocalDate.of(2026, 10, 22), LocalDateTime.of(2026, 10, 22, 17, 30));
        assertThat(ProductionRequestService.autoCompleteAt(r)).isEqualTo(LocalDateTime.of(2026, 10, 23, 20, 0));
    }

    @Test
    void lateReceiptOrCancelledCompletionMovesDeadline() {
        ProductionRequest r = received(LocalDate.of(2026, 10, 22), LocalDateTime.of(2026, 10, 24, 9, 0));
        assertThat(ProductionRequestService.autoCompleteAt(r)).isEqualTo(LocalDateTime.of(2026, 10, 25, 20, 0));
        ProductionRequestRevision cancel = new ProductionRequestRevision();
        cancel.setNote("Завершение отменено — требование-накладная в 1С распроведена");
        cancel.setCreatedAt(LocalDateTime.of(2026, 10, 26, 10, 0));
        r.getRevisions().add(cancel);
        assertThat(ProductionRequestService.autoCompleteAt(r)).isEqualTo(LocalDateTime.of(2026, 10, 27, 20, 0));
    }

    @Test
    void notForCompletedCollectedOrOld() {
        ProductionRequest done = received(LocalDate.of(2026, 10, 22), null);
        done.setRequisitionCompleted(true);
        assertThat(ProductionRequestService.autoCompleteAt(done)).isNull();
        ProductionRequest collected = received(LocalDate.of(2026, 10, 22), null);
        collected.setStatus(ProductionRequestStatus.COLLECTED);
        assertThat(ProductionRequestService.autoCompleteAt(collected)).isNull();
        assertThat(ProductionRequestService.autoCompleteAt(received(LocalDate.of(2026, 10, 20), null))).isNull();
    }
}
