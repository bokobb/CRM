package com.confectionery.crm.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InvoicePdfServiceTest {

    private final InvoicePdfService service = new InvoicePdfService(null);

    /** Данные образца 1С — Downloads/Print.pdf (Поступление №204 от 24.09.2026). */
    @Test
    void purchaseInvoiceMatchesOneCSample() throws IOException {
        OneCInvoiceService.Document doc = new OneCInvoiceService.Document("204", LocalDate.of(2026, 9, 24),
                "Fahri Group", "Юг Сладости", "Без договора", "Сырье", List.of(
                new OneCInvoiceService.Line("00000000032", "Сахар", new BigDecimal("2500"), "шт",
                        new BigDecimal("290.00"), new BigDecimal("725000.00")),
                new OneCInvoiceService.Line("00000000127", "Подсолнечное масло \"Сергеевское\"", new BigDecimal("1035"),
                        "шт", new BigDecimal("728.26"), new BigDecimal("753750.00"))));

        byte[] pdf = service.renderPurchase(doc);

        // для визуальной сверки с образцом 1С
        Path out = Path.of("build", "test-output", "purchase-invoice.pdf");
        Files.createDirectories(out.getParent());
        Files.write(out, pdf);
        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertThat(document.getNumberOfPages()).isEqualTo(1);
            String text = new PDFTextStripper().getText(document);
            assertThat(text)
                    .contains("Приходная накладная № 204 от 24 сентября 2026 г.")
                    .contains("Поставщик:", "Fahri Group", "Покупатель:", "Юг Сладости", "Склад:", "Сырье")
                    .contains("2,500", "1,035", "728.26", "753,750.00")
                    .contains("Итого:", "1,478,750.00")
                    .contains("Всего наименований 2, на сумму 1,478,750.00 теңге")
                    .contains("Один миллион четыреста семьдесят восемь тысяч семьсот пятьдесят теңге 00 тиын")
                    .contains("Отпустил", "Получил");
        }
    }
}
