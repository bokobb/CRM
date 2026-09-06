package com.confectionery.crm.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Отчёт по производству (сколько готовой продукции реально сделали за один день, за период
 * или за всё время — {@code from}/{@code to} оба null) — простая таблица, шрифт/приёмы вёрстки
 * те же, что в InvoicePdfService (DejaVuSans для кириллицы), но сама вёрстка не переиспользуется
 * — это принципиально другой документ (без денег/клиента, просто товар-количество-отклонение).
 */
@Service
public class ProductionReportPdfService {

    private static final float MARGIN = 40;
    private static final float[] COL_WIDTHS = {26, 175, 55, 55, 55, 65, 34};
    private static final int COL_NUM = 0, COL_NAME = 1, COL_BATCHES = 2, COL_NORM = 3, COL_FACT = 4, COL_DEVIATION = 5,
            COL_UNIT = 6;
    private static final DecimalFormat QTY_FORMAT = qtyFormat();
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private static DecimalFormat qtyFormat() {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.forLanguageTag("ru"));
        return new DecimalFormat("#,##0.###", symbols);
    }

    public byte[] generate(LocalDate from, LocalDate to, List<ProductionRequestService.ReportLine> lines) {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            PDFont regular = loadFont(document, "DejaVuSans.ttf");
            PDFont bold = loadFont(document, "DejaVuSans-Bold.ttf");
            float pageHeight = PDRectangle.A4.getHeight();
            float tableWidth = sum(COL_WIDTHS);

            try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                float y = pageHeight - MARGIN;

                text(cs, bold, 14, MARGIN, y, "Отчёт по производству за " + periodLabel(from, to));
                y -= 12;
                cs.setLineWidth(1f);
                cs.moveTo(MARGIN, y);
                cs.lineTo(MARGIN + tableWidth, y);
                cs.stroke();
                y -= 22;

                y = drawTable(cs, regular, bold, lines, MARGIN, y);

                y -= 20;
                text(cs, regular, 10.5f, MARGIN, y,
                        lines.isEmpty() ? "За этот период ничего не оприходовано." : "Всего позиций: " + lines.size());
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось сгенерировать отчёт", e);
        }
    }

    private float drawTable(PDPageContentStream cs, PDFont regular, PDFont bold,
                             List<ProductionRequestService.ReportLine> lines, float x0, float startY) throws IOException {
        float rowHeight = 18;
        float headerHeight = 20;
        float tableWidth = sum(COL_WIDTHS);
        float tableHeight = headerHeight + Math.max(1, lines.size()) * rowHeight;

        cs.setLineWidth(0.8f);
        cs.addRect(x0, startY - tableHeight, tableWidth, tableHeight);
        cs.stroke();
        cs.setLineWidth(0.4f);
        float colX = x0;
        for (int c = 0; c < COL_WIDTHS.length - 1; c++) {
            colX += COL_WIDTHS[c];
            cs.moveTo(colX, startY);
            cs.lineTo(colX, startY - tableHeight);
            cs.stroke();
        }
        float rowLineY = startY - headerHeight;
        cs.moveTo(x0, rowLineY);
        cs.lineTo(x0 + tableWidth, rowLineY);
        cs.stroke();
        for (int i = 0; i < lines.size(); i++) {
            rowLineY -= rowHeight;
            cs.moveTo(x0, rowLineY);
            cs.lineTo(x0 + tableWidth, rowLineY);
            cs.stroke();
        }

        centeredText(cs, bold, 9f, colX(x0, COL_NUM), COL_WIDTHS[COL_NUM], startY - 14, "№");
        centeredText(cs, bold, 9f, colX(x0, COL_NAME), COL_WIDTHS[COL_NAME], startY - 14, "Товар");
        centeredText(cs, bold, 9f, colX(x0, COL_BATCHES), COL_WIDTHS[COL_BATCHES], startY - 14, "Замесов");
        centeredText(cs, bold, 9f, colX(x0, COL_NORM), COL_WIDTHS[COL_NORM], startY - 14, "Норма");
        centeredText(cs, bold, 9f, colX(x0, COL_FACT), COL_WIDTHS[COL_FACT], startY - 14, "Факт");
        centeredText(cs, bold, 9f, colX(x0, COL_DEVIATION), COL_WIDTHS[COL_DEVIATION], startY - 14, "Отклонение");
        centeredText(cs, bold, 9f, colX(x0, COL_UNIT), COL_WIDTHS[COL_UNIT], startY - 14, "Ед.");

        float y = startY - headerHeight;
        int i = 1;
        for (ProductionRequestService.ReportLine line : lines) {
            float cellTop = y - 13;
            text(cs, regular, 9.5f, colX(x0, COL_NUM) + 4, cellTop, String.valueOf(i++));
            text(cs, regular, 9.5f, colX(x0, COL_NAME) + 4, cellTop, line.productName());
            rightText(cs, regular, 9.5f, colX(x0, COL_BATCHES) + COL_WIDTHS[COL_BATCHES] - 4, cellTop,
                    QTY_FORMAT.format(line.batches()));
            rightText(cs, regular, 9.5f, colX(x0, COL_NORM) + COL_WIDTHS[COL_NORM] - 4, cellTop,
                    QTY_FORMAT.format(line.norm()));
            rightText(cs, bold, 9.5f, colX(x0, COL_FACT) + COL_WIDTHS[COL_FACT] - 4, cellTop,
                    QTY_FORMAT.format(line.received()));
            rightText(cs, bold, 9.5f, colX(x0, COL_DEVIATION) + COL_WIDTHS[COL_DEVIATION] - 4, cellTop,
                    formatDeviation(line.deviation()));
            text(cs, regular, 9.5f, colX(x0, COL_UNIT) + 4, cellTop, line.unit());
            y -= rowHeight;
        }

        return startY - tableHeight;
    }

    private String periodLabel(LocalDate from, LocalDate to) {
        if (from == null || to == null) return "всё время";
        if (from.equals(to)) return DATE_FORMAT.format(from);
        return DATE_FORMAT.format(from) + " – " + DATE_FORMAT.format(to);
    }

    private String formatDeviation(java.math.BigDecimal deviation) {
        String formatted = QTY_FORMAT.format(deviation.abs());
        if (deviation.signum() > 0) return "+" + formatted;
        if (deviation.signum() < 0) return "-" + formatted;
        return formatted;
    }

    private float colX(float x0, int colIndex) {
        float x = x0;
        for (int c = 0; c < colIndex; c++) x += COL_WIDTHS[c];
        return x;
    }

    private float text(PDPageContentStream cs, PDFont font, float size, float x, float y, String value) throws IOException {
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(value);
        cs.endText();
        return y - size - 4;
    }

    private void rightText(PDPageContentStream cs, PDFont font, float size, float rightEdge, float y, String value)
            throws IOException {
        text(cs, font, size, rightEdge - width(font, size, value), y, value);
    }

    private void centeredText(PDPageContentStream cs, PDFont font, float size, float colLeft, float colWidth,
                               float y, String value) throws IOException {
        float x = colLeft + (colWidth - width(font, size, value)) / 2;
        text(cs, font, size, x, y, value);
    }

    private float width(PDFont font, float size, String text) throws IOException {
        return font.getStringWidth(text) / 1000 * size;
    }

    private float sum(float[] values) {
        float s = 0;
        for (float v : values) s += v;
        return s;
    }

    private PDFont loadFont(PDDocument document, String filename) {
        try (InputStream in = new ClassPathResource("fonts/" + filename).getInputStream()) {
            return PDType0Font.load(document, in);
        } catch (IOException e) {
            throw new UncheckedIOException("Не найден шрифт " + filename, e);
        }
    }
}
