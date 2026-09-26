package com.confectionery.crm.service;

import com.confectionery.crm.domain.Order;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.beans.factory.annotation.Value;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Накладная клиенту в PDF — повторяет печатные формы 1С "Расходная накладная" и "Возврат от
 * покупателя": все координаты, размеры шрифтов и толщины линий сняты с PDF, распечатанных
 * из 1С пользователем 26.09 (Downloads/Накладное Продажа.pdf, Возврат НАкладное.pdf). Шрифт в
 * 1С — Arial, здесь метрически совместимый с ним Liberation Sans. Данные — из документов 1С
 * (см. OneCInvoiceService). Если за заказом закреплены возвраты — они идут в тот же файл
 * следом за продажей, а в конце итог "продажа − возвраты = к оплате".
 * Генерируется на backend (см. OrderService.ship — уходит клиенту в WhatsApp при отгрузке,
 * и OrderApiController.invoice — кнопка "Поделиться" в приложении).
 */
@Service
public class InvoicePdfService {

    private static final String[] MONTHS_GENITIVE = {
            "января", "февраля", "марта", "апреля", "мая", "июня",
            "июля", "августа", "сентября", "октября", "ноября", "декабря"
    };
    private static final float PAGE_HEIGHT = PDRectangle.A4.getHeight();
    private static final float PAGE_TOP = PAGE_HEIGHT - 36;
    private static final float PAGE_BOTTOM = 36;
    /** Линии таблиц и отчёркивания в 1С — не чёрные, а тёмно-серые. */
    private static final float LINE_GRAY = 0.2f;
    private static final float DOCUMENT_GAP = 36;

    /** Расходная накладная — геометрия образца на A4. */
    private static final Layout SALE = new Layout(
            new float[]{36.48f, 66.72f, 123.00f, 273.60f, 318.96f, 333.96f, 392.40f, 477.24f},
            new String[]{"№ п/п", "Код", "Товар", "Количество", null, "Цена", "Сумма"},
            11.88f, 39.36f, 4.36f, 477.24f, 1.27f,
            8.52f, 38.40f, 155.28f, "", 17.12f, 17.28f, 9.16f,
            1.27f, 0.64f, 27.88f, 8.52f, 16.52f,
            10.2f, 7.68f, 7.48f, 1.8f, 2.76f, 2.0f, false, qtyFormat("#,##0.000"),
            12.68f, 8.52f, 363.48f, 1.62f,
            19.56f, 7.68f, 38.28f, true, 11.28f, 8.52f, 38.64f);

    /** Возврат от покупателя — геометрия образца (там Letter, на A4 помещается как есть). */
    private static final Layout RETURN = new Layout(
            new float[]{42.00f, 73.44f, 154.20f, 343.20f, 390.48f, 421.92f, 469.20f, 559.20f},
            new String[]{"№", "Код", "Товар", "Кол-во", "Ед.", "Цена", "Сумма"},
            14.04f, 45.48f, 6.27f, 547.92f, 1.5f,
            9.84f, 44.28f, 125.16f, ":", 20.01f, 19.92f, 10.71f,
            1.5f, 0.75f, 12.82f, 9.84f, 9.33f,
            10.8f, 8.04f, 7.41f, 1.92f, 1.92f, 1.7f, true, qtyFormat("#,##0.###"),
            16.89f, 9.84f, 435.72f, 1.7f,
            18.36f, 8.04f, 43.92f, false, 12.6f, 9.84f, 44.40f);

    private static final DecimalFormat MONEY = new DecimalFormat("#,##0.00", new DecimalFormatSymbols(Locale.US));

    private final OneCInvoiceService oneCInvoiceService;
    private final String returnReceivedBy;

    public InvoicePdfService(OneCInvoiceService oneCInvoiceService,
                             @Value("${crm.invoice.return-received-by:Пайзахматов Б.}") String returnReceivedBy) {
        this.oneCInvoiceService = oneCInvoiceService;
        this.returnReceivedBy = returnReceivedBy;
    }

    public byte[] generate(Order order) {
        return render(oneCInvoiceService.load(order));
    }

    byte[] render(OneCInvoiceService.Invoice invoice) {
        try (PDDocument document = new PDDocument()) {
            Canvas canvas = new Canvas(document,
                    loadFont(document, "LiberationSans-Regular.ttf"),
                    loadFont(document, "LiberationSans-Bold.ttf"));
            canvas.newPage();
            // заголовок первой накладной — там же, где в образце 1С
            canvas.y = 792.36f;

            OneCInvoiceService.Document sale = invoice.sale();
            drawDocument(canvas, SALE, "Расходная накладная № " + sale.number() + " от " + formatDate(sale.date()),
                    headerRows(sale, "Поставщик", "Покупатель"), sale.lines());
            drawSaleSignatures(canvas);

            for (OneCInvoiceService.Document ret : invoice.returns()) {
                canvas.y -= DOCUMENT_GAP;
                drawDocument(canvas, RETURN, "Возврат от покупателя № " + ret.number() + " от " + formatDate(ret.date()),
                        headerRows(ret, "Поставщик", "Покупатель"), ret.lines());
                drawReturnSignatures(canvas);
            }
            if (!invoice.returns().isEmpty()) {
                drawPayable(canvas, invoice);
            }
            canvas.close();

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось сгенерировать накладную", e);
        }
    }

    private List<String[]> headerRows(OneCInvoiceService.Document doc, String supplierLabel, String buyerLabel) {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{supplierLabel, doc.supplier()});
        rows.add(new String[]{buyerLabel, doc.buyer()});
        rows.add(new String[]{"Основание", doc.contract()});
        if (doc.warehouse() != null && !doc.warehouse().isBlank()) {
            rows.add(new String[]{"Склад", doc.warehouse()});
        }
        return rows;
    }

    /**
     * Заголовок, шапка, таблица и итоги одного документа. canvas.y на входе — базовая линия
     * заголовка, на выходе — базовая линия суммы прописью.
     */
    private void drawDocument(Canvas c, Layout l, String title, List<String[]> header,
                              List<OneCInvoiceService.Line> lines) throws IOException {
        // заголовок + шапка + шапка таблицы + хотя бы одна строка должны оказаться на одной странице
        float headBlock = l.ruleGap + l.labelFirstGap + (header.size() - 1) * l.labelStep + l.tableGap
                + l.headerHeight + l.rowHeight + l.titleSize;
        if (c.y - headBlock < PAGE_BOTTOM) {
            c.newPage();
            c.y = PAGE_TOP - l.titleSize;
        }
        float left = l.cols[0];
        c.text(c.bold, l.titleSize, l.titleX, c.y, title);
        float ruleY = c.y - l.ruleGap;
        c.hline(left, l.ruleRight, ruleY, l.ruleWidth, LINE_GRAY);

        float y = ruleY - l.labelFirstGap;
        for (int i = 0; i < header.size(); i++) {
            if (i > 0) y -= l.labelStep;
            c.text(c.regular, l.labelSize, l.labelX, y, header.get(i)[0] + l.labelSuffix);
            c.text(c.bold, l.labelSize, l.valueX, y - 0.12f, header.get(i)[1]);
        }
        c.y = y - l.tableGap;
        drawTable(c, l, lines);

        BigDecimal total = lines.stream().map(OneCInvoiceService.Line::sum).reduce(BigDecimal.ZERO, BigDecimal::add);
        float footer = l.totalGap + l.countGap + l.wordsGap;
        if (c.y - footer < PAGE_BOTTOM) {
            c.newPage();
            c.y = PAGE_TOP + l.totalGap - l.totalSize;
        }
        c.y -= l.totalGap;
        c.text(c.bold, l.totalSize, l.totalLabelX, c.y, "Итого:");
        c.rightText(c.bold, l.totalSize, l.cols[7] - l.totalRightPad, c.y, money(total));

        c.y -= l.countGap;
        String count = "Всего наименований " + lines.size() + ", на сумму " + money(total) + " теңге";
        c.text(c.regular, l.countSize, l.countX, c.y, count);
        if (l.countUnderline) {
            c.hline(l.countX, l.countX + c.width(c.regular, l.countSize, count), c.y - 0.84f, 0.6f, 0f);
        }
        c.y -= l.wordsGap;
        c.text(c.bold, l.wordsSize, l.wordsX, c.y, NumberToWordsRu.amountInWords(total));
    }

    private void drawTable(Canvas c, Layout l, List<OneCInvoiceService.Line> lines) throws IOException {
        float[] cols = l.cols;
        float nameWidth = cols[3] - cols[2] - 2 * l.pad;
        float segmentTop = c.y;
        drawTableHeader(c, l);
        float y = c.y - l.headerHeight;

        int number = 1;
        for (OneCInvoiceService.Line line : lines) {
            List<String> nameLines = c.wrap(c.regular, l.rowSize, line.name(), nameWidth);
            float rowHeight = l.rowHeight + (nameLines.size() - 1) * (l.rowSize * 1.15f);
            if (y - rowHeight < PAGE_BOTTOM) {
                closeTableSegment(c, l, segmentTop, y);
                c.newPage();
                c.y = PAGE_TOP;
                segmentTop = c.y;
                drawTableHeader(c, l);
                y = c.y - l.headerHeight;
            }
            float baseline = y - l.rowBaseline;
            String num = String.valueOf(number++);
            if (l.centerNumber) {
                c.centeredText(c.regular, l.rowSize, cols[0], cols[1] - cols[0], baseline, num);
            } else {
                c.text(c.regular, l.rowSize, cols[0] + l.pad, baseline, num);
            }
            c.text(c.regular, l.rowSize, cols[1] + l.pad, baseline, line.code());
            float ny = baseline;
            for (String part : nameLines) {
                c.text(c.regular, l.rowSize, cols[2] + l.pad, ny, part);
                ny -= l.rowSize * 1.15f;
            }
            c.rightText(c.regular, l.rowSize, cols[4] - l.rightPad, baseline, l.qtyFormat.format(line.quantity()));
            c.text(c.regular, l.rowSize, cols[4] + l.unitPad, baseline, line.unit());
            c.rightText(c.regular, l.rowSize, cols[6] - l.rightPad, baseline, money(line.price()));
            c.rightText(c.regular, l.rowSize, cols[7] - l.rightPad, baseline, money(line.sum()));
            y -= rowHeight;
            c.hline(cols[0], cols[7], y, l.thin, LINE_GRAY);
        }
        closeTableSegment(c, l, segmentTop, y);
        c.y = y - l.thin / 2;
    }

    private void drawTableHeader(Canvas c, Layout l) throws IOException {
        float[] cols = l.cols;
        float top = c.y;
        float bottom = top - l.headerHeight;
        c.hline(cols[0], cols[7], top, l.thick, LINE_GRAY);
        c.hline(cols[0], cols[7], bottom, l.thin, LINE_GRAY);
        float baseline = top - l.headerBaseline;
        for (int i = 0; i < l.headers.length; i++) {
            if (l.headers[i] == null) continue;
            // null в следующей колонке — заголовок объединён с ней ("Количество" над кол-вом и ед. изм.)
            int to = (i + 1 < l.headers.length && l.headers[i + 1] == null) ? i + 2 : i + 1;
            c.centeredText(c.bold, l.headerSize, cols[i], cols[to] - cols[i], baseline, l.headers[i]);
        }
    }

    /** Вертикальные линии и толстая нижняя граница куска таблицы от segmentTop до bottom. */
    private void closeTableSegment(Canvas c, Layout l, float segmentTop, float bottom) throws IOException {
        float[] cols = l.cols;
        float headerBottom = segmentTop - l.headerHeight;
        float lineBottom = bottom - l.thin / 2;
        c.hline(cols[0], cols[7], lineBottom, l.thick, LINE_GRAY);
        c.vline(cols[0] + l.thick / 2, lineBottom, segmentTop, l.thick);
        c.vline(cols[7] + l.thick / 2, lineBottom, segmentTop, l.thick);
        for (int i = 1; i < cols.length - 1; i++) {
            boolean merged = l.headers[i] == null;
            c.vline(cols[i] + l.thin / 2, lineBottom, merged ? headerBottom : segmentTop, l.thin);
        }
    }

    private void drawSaleSignatures(Canvas c) throws IOException {
        float gap = 29.76f;
        if (c.y - gap < PAGE_BOTTOM) {
            c.newPage();
            c.y = PAGE_TOP + gap;
        }
        c.y -= gap;
        float size = 8.52f;
        c.text(c.bold, size, 38.64f, c.y, "Отпустил");
        c.hline(81.72f, 243.48f, c.y - 2.96f, 0.64f, LINE_GRAY);
        c.text(c.regular, 7.68f, 237.48f, c.y - 0.24f, "/");
        c.text(c.bold, size, 260.76f, c.y, "Получил");
        c.hline(303.84f, 477.24f, c.y - 2.96f, 0.64f, LINE_GRAY);
        c.text(c.regular, 7.68f, 385.00f, c.y - 0.24f, "/");
    }

    private void drawReturnSignatures(Canvas c) throws IOException {
        float ruleGap = 10.59f;
        float signGap = 20.01f;
        if (c.y - ruleGap - signGap < PAGE_BOTTOM) {
            c.newPage();
            c.y = PAGE_TOP + ruleGap;
        }
        c.y -= ruleGap;
        c.hline(42.00f, 559.20f, c.y, 1.5f, LINE_GRAY);
        c.y -= signGap;
        float size = 9.84f;
        c.text(c.bold, size, 44.40f, c.y, "Отпустил");
        c.hline(89.16f, 295.92f, c.y - 3.37f, 0.75f, LINE_GRAY);
        c.text(c.bold, size, 314.16f, c.y, "Получил");
        c.hline(358.92f, 547.92f, c.y - 3.37f, 0.75f, LINE_GRAY);
        c.rightText(c.regular, 8.04f, 547.92f, c.y - 0.48f, returnReceivedBy);
    }

    /** Итог внизу: продажа, каждый возврат со знаком минус и "к оплате" — в стиле подвала возврата. */
    private void drawPayable(Canvas c, OneCInvoiceService.Invoice invoice) throws IOException {
        float left = 42.00f;
        float right = 559.20f - 1.7f;
        float size = 9.84f;
        float step = 16f;
        float height = 30 + 14 + step * (invoice.returns().size() + 1) + 20 + 26;
        c.y -= DOCUMENT_GAP;
        if (c.y - height < PAGE_BOTTOM) {
            c.newPage();
            c.y = PAGE_TOP;
        }
        c.text(c.bold, 14.04f, 45.48f, c.y, "Итог");
        c.y -= 6.27f;
        c.hline(left, 547.92f, c.y, 1.5f, LINE_GRAY);

        OneCInvoiceService.Document sale = invoice.sale();
        c.y -= 20.01f;
        c.text(c.regular, size, 44.28f, c.y,
                "Расходная накладная № " + sale.number() + " от " + formatDate(sale.date()));
        c.rightText(c.regular, size, right, c.y, money(sale.total()));
        for (OneCInvoiceService.Document ret : invoice.returns()) {
            c.y -= step;
            c.text(c.regular, size, 44.28f, c.y,
                    "Возврат от покупателя № " + ret.number() + " от " + formatDate(ret.date()));
            c.rightText(c.regular, size, right, c.y, "−" + money(ret.total()));
        }
        c.y -= 8;
        c.hline(left, 559.20f, c.y, 1.5f, LINE_GRAY);
        c.y -= 16.89f;
        BigDecimal payable = invoice.payable();
        c.text(c.bold, size, 44.28f, c.y, "Итого к оплате:");
        c.rightText(c.bold, size, right, c.y, money(payable));
        c.y -= 18.36f;
        c.text(c.regular, 8.04f, 43.92f, c.y, "Сумма к оплате " + money(payable) + " теңге");
        c.y -= 12.6f;
        c.text(c.bold, size, 44.40f, c.y, NumberToWordsRu.amountInWords(payable));
    }

    private static String money(BigDecimal value) {
        return MONEY.format(value.setScale(2, RoundingMode.HALF_UP));
    }

    private static String formatDate(LocalDate date) {
        return date.getDayOfMonth() + " " + MONTHS_GENITIVE[date.getMonthValue() - 1] + " " + date.getYear() + " г.";
    }

    private static DecimalFormat qtyFormat(String pattern) {
        return new DecimalFormat(pattern, new DecimalFormatSymbols(Locale.US));
    }

    private static PDFont loadFont(PDDocument document, String filename) {
        try (InputStream in = new ClassPathResource("fonts/" + filename).getInputStream()) {
            return PDType0Font.load(document, in);
        } catch (IOException e) {
            throw new UncheckedIOException("Не найден шрифт " + filename, e);
        }
    }

    /**
     * Геометрия одной печатной формы. cols — 8 границ колонок (№, код, товар, кол-во, ед. изм.,
     * цена, сумма); headers — заголовки колонок, null — колонка объединена в шапке с предыдущей.
     * Все "...Gap"/"...Baseline" — расстояния по вертикали от предыдущего элемента, как в образце.
     */
    private record Layout(
            float[] cols, String[] headers,
            float titleSize, float titleX, float ruleGap, float ruleRight, float ruleWidth,
            float labelSize, float labelX, float valueX, String labelSuffix,
            float labelFirstGap, float labelStep, float tableGap,
            float thick, float thin, float headerHeight, float headerSize, float headerBaseline,
            float rowHeight, float rowSize, float rowBaseline, float pad, float unitPad, float rightPad,
            boolean centerNumber, DecimalFormat qtyFormat,
            float totalGap, float totalSize, float totalLabelX, float totalRightPad,
            float countGap, float countSize, float countX, boolean countUnderline,
            float wordsGap, float wordsSize, float wordsX) {
    }

    /** Текущая страница и позиция на ней (y — в координатах PDF, снизу вверх). */
    private static final class Canvas {
        final PDDocument document;
        final PDFont regular;
        final PDFont bold;
        PDPageContentStream cs;
        float y;

        Canvas(PDDocument document, PDFont regular, PDFont bold) {
            this.document = document;
            this.regular = regular;
            this.bold = bold;
        }

        void newPage() throws IOException {
            close();
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            cs = new PDPageContentStream(document, page);
            y = PAGE_TOP;
        }

        void close() throws IOException {
            if (cs != null) {
                cs.close();
                cs = null;
            }
        }

        void text(PDFont font, float size, float x, float baseline, String value) throws IOException {
            if (value == null || value.isEmpty()) return;
            cs.beginText();
            cs.setFont(font, size);
            cs.newLineAtOffset(x, baseline);
            cs.showText(value);
            cs.endText();
        }

        void rightText(PDFont font, float size, float rightEdge, float baseline, String value) throws IOException {
            text(font, size, rightEdge - width(font, size, value), baseline, value);
        }

        void centeredText(PDFont font, float size, float left, float width, float baseline, String value)
                throws IOException {
            text(font, size, left + (width - width(font, size, value)) / 2, baseline, value);
        }

        void hline(float x1, float x2, float lineY, float lineWidth, float gray) throws IOException {
            cs.setStrokingColor(gray, gray, gray);
            cs.setLineWidth(lineWidth);
            cs.moveTo(x1, lineY);
            cs.lineTo(x2, lineY);
            cs.stroke();
        }

        void vline(float x, float y1, float y2, float lineWidth) throws IOException {
            cs.setStrokingColor(LINE_GRAY, LINE_GRAY, LINE_GRAY);
            cs.setLineWidth(lineWidth);
            cs.moveTo(x, y1);
            cs.lineTo(x, y2);
            cs.stroke();
        }

        float width(PDFont font, float size, String text) throws IOException {
            return font.getStringWidth(text) / 1000 * size;
        }

        List<String> wrap(PDFont font, float size, String text, float maxWidth) throws IOException {
            List<String> lines = new ArrayList<>();
            StringBuilder line = new StringBuilder();
            for (String word : text.split(" ")) {
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (width(font, size, candidate) > maxWidth && !line.isEmpty()) {
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
    }
}
