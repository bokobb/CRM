package com.confectionery.crm.service;

import com.confectionery.crm.domain.Order;
import com.confectionery.crm.domain.OrderItem;
import lombok.RequiredArgsConstructor;
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
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Расходная накладная в PDF — вёрстка скопирована с образца пользователя
 * (Downloads/Новый.xls, экспорт из прежней бухгалтерской программы): пропорции колонок
 * таблицы сняты по пикселям с рендера того файла. Генерируется на backend (см.
 * OrderService.ship), чтобы уходить клиенту в WhatsApp автоматически независимо от того,
 * с какого устройства кладовщик нажал "Отгрузил". Не переиспользует
 * mobile/lib/services/invoice_pdf.dart (та — для ручного "поделиться" из приложения,
 * упрощённый вид) — своя реализация через PDFBox.
 */
@Service
@RequiredArgsConstructor
public class InvoicePdfService {

    private final CompanyProfileService companyProfileService;

    private static final String[] MONTHS_GENITIVE = {
            "января", "февраля", "марта", "апреля", "мая", "июня",
            "июля", "августа", "сентября", "октября", "ноября", "декабря"
    };
    private static final float MARGIN = 40;
    // № п/п / Товар / Количество / (ед.изм.) / Цена / Сумма — пропорции сняты по пикселям с образца
    private static final float[] COL_WIDTHS = {36, 244, 55, 20, 65, 95};
    private static final int COL_NUM = 0, COL_NAME = 1, COL_QTY = 2, COL_UNIT = 3, COL_PRICE = 4, COL_SUM = 5;
    private static final DecimalFormat MONEY_TABLE = tableMoneyFormat();
    private static final DecimalFormat MONEY_SUMMARY = summaryMoneyFormat();
    private static final DecimalFormat QTY_FORMAT = qtyFormat();

    private static DecimalFormat tableMoneyFormat() {
        // запятая — разделитель тысяч, точка — десятичный (как в образце пользователя)
        return new DecimalFormat("#,##0.00", new DecimalFormatSymbols(Locale.US));
    }

    private static DecimalFormat summaryMoneyFormat() {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.forLanguageTag("ru"));
        symbols.setGroupingSeparator(' ');
        return new DecimalFormat("#,##0.00", symbols);
    }

    private static DecimalFormat qtyFormat() {
        return new DecimalFormat("0.000", new DecimalFormatSymbols(Locale.US));
    }

    public byte[] generate(Order order) {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            PDFont regular = loadFont(document, "DejaVuSans.ttf");
            PDFont bold = loadFont(document, "DejaVuSans-Bold.ttf");
            float pageHeight = PDRectangle.A4.getHeight();
            float tableWidth = sum(COL_WIDTHS);

            try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                float y = pageHeight - MARGIN;

                LocalDateTime shippedAt = order.getShippedAt() != null ? order.getShippedAt() : LocalDateTime.now();
                String title = "Расходная накладная № " + String.format("%011d", order.getId())
                        + " от " + formatDateGenitive(shippedAt);
                text(cs, bold, 14, MARGIN, y, title);
                y -= 12;
                cs.setLineWidth(1f);
                cs.moveTo(MARGIN, y);
                cs.lineTo(MARGIN + tableWidth, y);
                cs.stroke();
                y -= 22;

                String supplier = companyProfileService.get().getName();
                if (supplier == null || supplier.isBlank()) supplier = "Юг Сладости";
                y = labelValueRow(cs, regular, bold, MARGIN, y, "Поставщик", supplier);
                y = labelValueRow(cs, regular, bold, MARGIN, y, "Покупатель", order.getClient().getName());
                y -= 12;

                y = drawTable(cs, regular, bold, order.getItems(), MARGIN, y);

                y -= 22;
                BigDecimal total = order.getTotalPrice().setScale(2, RoundingMode.HALF_UP);
                float colPriceRight = MARGIN + sum(COL_WIDTHS, COL_PRICE + 1);
                float colSumRight = MARGIN + tableWidth;
                rightText(cs, bold, 11, colPriceRight - 4, y, "Итого:");
                rightText(cs, bold, 11, colSumRight - 4, y, MONEY_TABLE.format(total));
                y -= 26;

                String summary = "Всего наименований " + order.getItems().size() + ", на сумму "
                        + MONEY_SUMMARY.format(total) + " теңге";
                float summaryY = y;
                y = text(cs, regular, 10.5f, MARGIN, y, summary);
                cs.setLineWidth(0.6f);
                cs.moveTo(MARGIN, summaryY - 2);
                cs.lineTo(MARGIN + width(regular, 10.5f, summary), summaryY - 2);
                cs.stroke();

                y = text(cs, bold, 10.5f, MARGIN, y, NumberToWordsRu.amountInWords(total));
                y -= 24;

                signatureLine(cs, regular, bold, MARGIN, y, "Отпустил");
                signatureLine(cs, regular, bold, MARGIN + tableWidth / 2 + 10, y, "Получил");
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось сгенерировать накладную", e);
        }
    }

    private void signatureLine(PDPageContentStream cs, PDFont regular, PDFont bold, float x, float y, String label)
            throws IOException {
        text(cs, bold, 11, x, y, label);
        float lineStart = x + width(bold, 11, label) + 8;
        float lineEnd = lineStart + 150;
        cs.setLineWidth(0.6f);
        cs.moveTo(lineStart, y - 2);
        cs.lineTo(lineEnd, y - 2);
        cs.stroke();
        text(cs, regular, 11, lineEnd + 4, y, "/");
    }

    private float labelValueRow(PDPageContentStream cs, PDFont regular, PDFont bold, float x0, float y,
                                 String label, String value) throws IOException {
        text(cs, regular, 11, x0, y, label);
        text(cs, bold, 11, x0 + 110, y, value);
        return y - 17;
    }

    private float drawTable(PDPageContentStream cs, PDFont regular, PDFont bold, List<OrderItem> items,
                             float x0, float startY) throws IOException {
        float rowPad = 4;
        record Row(String number, List<String> nameLines, String qty, String unit, String price, String sum, float height) {
        }
        List<Row> rows = new ArrayList<>();
        int i = 1;
        for (OrderItem item : items) {
            List<String> nameLines = wrap(regular, 9, item.getProduct().getName(), COL_WIDTHS[COL_NAME] - 2 * rowPad);
            float height = Math.max(16, nameLines.size() * 11 + 6);
            String unit = item.getProduct().getUnit();
            rows.add(new Row(
                    String.valueOf(i++),
                    nameLines,
                    QTY_FORMAT.format(item.getQuantity()),
                    (unit == null || unit.isBlank()) ? "шт" : unit,
                    MONEY_TABLE.format(item.getPrice().setScale(2, RoundingMode.HALF_UP)),
                    MONEY_TABLE.format(item.getPrice().multiply(item.getQuantity()).setScale(2, RoundingMode.HALF_UP)),
                    height
            ));
        }

        float headerHeight = 20;
        float tableWidth = sum(COL_WIDTHS);
        float tableHeight = headerHeight + rows.stream().map(Row::height).reduce(0f, Float::sum);

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
        float rowY = startY - headerHeight;
        cs.moveTo(x0, rowY);
        cs.lineTo(x0 + tableWidth, rowY);
        cs.stroke();
        for (Row row : rows) {
            rowY -= row.height();
            cs.moveTo(x0, rowY);
            cs.lineTo(x0 + tableWidth, rowY);
            cs.stroke();
        }

        // заголовки — по центру своей колонки; "Количество" по центру объединённых кол-во+ед.изм.
        centeredText(cs, bold, 9.5f, colX(x0, COL_NUM), COL_WIDTHS[COL_NUM], startY - 14, "№ п/п");
        centeredText(cs, bold, 9.5f, colX(x0, COL_NAME), COL_WIDTHS[COL_NAME], startY - 14, "Товар");
        centeredText(cs, bold, 9.5f, colX(x0, COL_QTY), COL_WIDTHS[COL_QTY] + COL_WIDTHS[COL_UNIT], startY - 14, "Количество");
        centeredText(cs, bold, 9.5f, colX(x0, COL_PRICE), COL_WIDTHS[COL_PRICE], startY - 14, "Цена");
        centeredText(cs, bold, 9.5f, colX(x0, COL_SUM), COL_WIDTHS[COL_SUM], startY - 14, "Сумма");

        float y = startY - headerHeight;
        for (Row row : rows) {
            float cellTop = y - 11;
            text(cs, regular, 9, colX(x0, COL_NUM) + rowPad, cellTop, row.number());
            float ny = cellTop;
            for (String line : row.nameLines()) {
                text(cs, regular, 9, colX(x0, COL_NAME) + rowPad, ny, line);
                ny -= 11;
            }
            // количество — крупнее и жирнее остальных колонок (то, что физически сверяют на складе)
            rightText(cs, bold, 10.5f, colX(x0, COL_QTY) + COL_WIDTHS[COL_QTY] - rowPad, cellTop, row.qty());
            text(cs, regular, 9, colX(x0, COL_UNIT) + rowPad, cellTop, row.unit());
            rightText(cs, regular, 9, colX(x0, COL_PRICE) + COL_WIDTHS[COL_PRICE] - rowPad, cellTop, row.price());
            rightText(cs, regular, 9, colX(x0, COL_SUM) + COL_WIDTHS[COL_SUM] - rowPad, cellTop, row.sum());
            y -= row.height();
        }

        return startY - tableHeight;
    }

    private float colX(float x0, int colIndex) {
        float x = x0;
        for (int c = 0; c < colIndex; c++) x += COL_WIDTHS[c];
        return x;
    }

    private String formatDateGenitive(LocalDateTime dateTime) {
        return dateTime.getDayOfMonth() + " " + MONTHS_GENITIVE[dateTime.getMonthValue() - 1] + " " + dateTime.getYear() + " г.";
    }

    private List<String> wrap(PDFont font, float fontSize, String text, float maxWidth) throws IOException {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (width(font, fontSize, candidate) > maxWidth && !line.isEmpty()) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (!line.isEmpty()) lines.add(line.toString());
        if (lines.isEmpty()) lines.add("");
        return lines;
    }

    private float text(PDPageContentStream cs, PDFont font, float size, float x, float y, String value) throws IOException {
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(value);
        cs.endText();
        return y - size - 4;
    }

    /** Текст с правым краем в точке [rightEdge]. */
    private void rightText(PDPageContentStream cs, PDFont font, float size, float rightEdge, float y, String value)
            throws IOException {
        text(cs, font, size, rightEdge - width(font, size, value), y, value);
    }

    /** Текст по центру диапазона [colLeft, colLeft + colWidth]. */
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

    private float sum(float[] values, int count) {
        float s = 0;
        for (int i = 0; i < count; i++) s += values[i];
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
