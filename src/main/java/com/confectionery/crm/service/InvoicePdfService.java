package com.confectionery.crm.service;

import com.confectionery.crm.domain.Order;
import com.confectionery.crm.domain.ProductReturn;
import com.confectionery.crm.domain.Purchase;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Накладная клиенту в PDF — повторяет печатную форму 1С "Расходная накладная": все координаты,
 * размеры шрифтов и толщины линий сняты с PDF, распечатанного из 1С пользователем 26.09
 * (Downloads/Накладное Продажа.pdf). Возврат печатается по тому же макету (решение пользователя —
 * вместо собственной формы 1С "Возврат от покупателя" с отдельными столбцами кол-ва и ед. изм.).
 * Шрифт в 1С — Arial, здесь метрически совместимый с ним Liberation Sans. Данные — из документов 1С
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

    /**
     * Образец 1С занимал лист не до конца (правый край 477.24 при левом 36.48) — по просьбе
     * пользователя растягиваем по горизонтали на всю ширину A4 с одинаковыми полями слева и справа.
     * Все x из образца пропускаются через {@link #sx}; шрифты и вертикаль не меняются.
     */
    private static final float SAMPLE_LEFT = 36.48f;
    private static final float SAMPLE_RIGHT = 477.24f;
    private static final float RIGHT = PDRectangle.A4.getWidth() - SAMPLE_LEFT;
    private static final float STRETCH = (RIGHT - SAMPLE_LEFT) / (SAMPLE_RIGHT - SAMPLE_LEFT);

    private static float sx(float sampleX) {
        return SAMPLE_LEFT + (sampleX - SAMPLE_LEFT) * STRETCH;
    }

    /** Геометрия образца "Расходной накладной" на A4 — по ней же печатается и возврат. */
    private static final Layout SALE = new Layout(
            new float[]{sx(36.48f), sx(66.72f), sx(123.00f), sx(273.60f), sx(318.96f), sx(333.96f), sx(392.40f), RIGHT},
            new String[]{"№ п/п", "Код", "Товар", "Количество", null, "Цена", "Сумма"},
            11.88f, 39.36f, 4.36f, RIGHT, 1.27f,
            8.52f, 38.40f, sx(155.28f), "", 17.12f, 17.28f, 9.16f,
            1.27f, 0.64f, 27.88f, 8.52f, 16.52f,
            10.2f, 7.68f, 7.48f, 1.8f, 2.76f, 2.0f, false, qtyFormat("#,##0.000"),
            12.68f, 8.52f, sx(363.48f), 1.62f,
            19.56f, 7.68f, 38.28f, true, 11.28f, 8.52f, 38.64f);

    /**
     * Приходная накладная — по печатной форме 1С "Поступление товаров и услуг" (Downloads/Print.pdf,
     * распечатан пользователем 27.09): свои размеры шрифтов и отступы, лист тоже растянут на всю
     * ширину A4 (x образца — через {@link #px}).
     */
    private static final float PURCHASE_SAMPLE_LEFT = 43.92f;
    private static final float PURCHASE_SAMPLE_RIGHT = 528.96f;
    private static final float PURCHASE_STRETCH =
            (RIGHT - SAMPLE_LEFT) / (PURCHASE_SAMPLE_RIGHT - PURCHASE_SAMPLE_LEFT);
    /** Базовая линия заголовка — на том же расстоянии от верха листа, что в образце (790.92 − 735.48). */
    private static final float PURCHASE_TITLE_Y = PAGE_HEIGHT - 55.44f;

    private static float px(float sampleX) {
        return SAMPLE_LEFT + (sampleX - PURCHASE_SAMPLE_LEFT) * PURCHASE_STRETCH;
    }

    private static final Layout PURCHASE = new Layout(
            new float[]{px(43.92f), px(61.68f), px(124.68f), px(282.24f), px(339.96f), px(366.24f), px(434.40f), RIGHT},
            new String[]{"№", "Код", "Товар", "Количество", null, "Цена", "Сумма"},
            14f, px(47.40f), 6.27f, px(513.24f), 1.5f,
            9f, px(46.20f), px(127.08f), ":", 20.01f, 19.76f, 10.47f,
            1.5f, 0.75f, 21.23f, 9f, 13.53f,
            10.8f, 8f, 7.78f, 1.92f, 1.92f, 1.7f, true, qtyFormat("#,##0.###"),
            15.92f, 9f, px(400.92f), 2.79f,
            18.36f, 8f, px(45.84f), false, 12.6f, 9f, px(46.32f));

    private static final DecimalFormat MONEY = new DecimalFormat("#,##0.00", new DecimalFormatSymbols(Locale.US));

    private final OneCInvoiceService oneCInvoiceService;
    public InvoicePdfService(OneCInvoiceService oneCInvoiceService) {
        this.oneCInvoiceService = oneCInvoiceService;
    }

    public byte[] generate(Order order) {
        return render(oneCInvoiceService.load(order));
    }

    /** Приходная накладная по закупке (кнопка "Поделиться" при свайпе закупки в приложении). */
    public byte[] generatePurchase(Purchase purchase) {
        return renderPurchase(oneCInvoiceService.loadPurchase(purchase));
    }

    /**
     * Накладная по одному возврату (свайп возврата вправо в приложении) — тот же блок
     * "Возврат от покупателя", что печатается под продажей в накладной заказа, но отдельным файлом.
     */
    public byte[] generateReturn(ProductReturn productReturn) {
        return renderReturn(oneCInvoiceService.loadReturn(productReturn),
                productReturn.getClient().getName().trim(), oneCInvoiceService.warehouseManager());
    }

    byte[] renderReturn(OneCInvoiceService.Document doc, String clientName, String warehouseManager) {
        try (PDDocument document = new PDDocument()) {
            Canvas canvas = new Canvas(document,
                    loadFont(document, "LiberationSans-Regular.ttf"),
                    loadFont(document, "LiberationSans-Bold.ttf"));
            canvas.newPage();
            canvas.y = 792.36f;
            drawDocument(canvas, SALE, "Возврат от покупателя № " + doc.number() + " от " + formatDate(doc.date()),
                    headerRows(doc, "Поставщик", "Покупатель"), doc.lines());
            // в возврате товар отпускает клиент, получает наш склад
            drawSignatures(canvas, clientName, warehouseManager);
            canvas.close();

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось сгенерировать накладную возврата", e);
        }
    }

    /** "Возврат_Покупатель_дд.мм.гггг.pdf" — дата возврата. */
    public static String returnFileName(ProductReturn productReturn) {
        StringBuilder name = new StringBuilder("Возврат");
        String client = productReturn.getClient() != null ? productReturn.getClient().getName() : null;
        if (client != null && !client.isBlank()) {
            name.append('_').append(client.replaceAll("[\\\\/:*?\"<>|\\s]+", " ").trim());
        }
        LocalDate date = productReturn.getReturnDate();
        name.append('_').append(String.format("%02d.%02d.%d", date.getDayOfMonth(), date.getMonthValue(), date.getYear()));
        return name.append(".pdf").toString();
    }

    byte[] renderPurchase(OneCInvoiceService.Document doc) {
        try (PDDocument document = new PDDocument()) {
            Canvas canvas = new Canvas(document,
                    loadFont(document, "LiberationSans-Regular.ttf"),
                    loadFont(document, "LiberationSans-Bold.ttf"));
            canvas.newPage();
            canvas.y = PURCHASE_TITLE_Y;
            drawDocument(canvas, PURCHASE, "Приходная накладная № " + doc.number() + " от " + formatDate(doc.date()),
                    headerRows(doc, "Поставщик", "Покупатель"), doc.lines());
            drawPurchaseSignatures(canvas);
            canvas.close();

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось сгенерировать приходную накладную", e);
        }
    }

    /** Жирная черта под суммой прописью и пустые строки подписей "Отпустил"/"Получил", как в образце. */
    private void drawPurchaseSignatures(Canvas c) throws IOException {
        float ruleGap = 10.59f;
        float signGap = 20.01f;
        if (c.y - ruleGap - signGap < PAGE_BOTTOM) {
            c.newPage();
            c.y = PAGE_TOP + ruleGap;
        }
        c.y -= ruleGap;
        c.hline(PURCHASE.cols[0], PURCHASE.cols[7], c.y, PURCHASE.thick, LINE_GRAY);
        c.y -= signGap;
        c.text(c.bold, 9f, px(46.32f), c.y, "Отпустил");
        c.hline(px(93.24f), px(282.24f), c.y - 3.38f, 0.75f, LINE_GRAY);
        c.text(c.bold, 9f, px(300.36f), c.y, "Получил");
        c.hline(px(350.40f), px(513.24f), c.y - 3.38f, 0.75f, LINE_GRAY);
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
            drawSignatures(canvas, invoice.warehouseManager(), invoice.clientName());

            for (OneCInvoiceService.Document ret : invoice.returns()) {
                canvas.y -= DOCUMENT_GAP;
                drawDocument(canvas, SALE, "Возврат от покупателя № " + ret.number() + " от " + formatDate(ret.date()),
                        headerRows(ret, "Поставщик", "Покупатель"), ret.lines());
                drawSignatures(canvas, invoice.clientName(), invoice.warehouseManager());
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

    private void drawSignatures(Canvas c, String releasedBy, String receivedBy) throws IOException {
        float gap = 29.76f;
        if (c.y - gap < PAGE_BOTTOM) {
            c.newPage();
            c.y = PAGE_TOP + gap;
        }
        c.y -= gap;
        c.text(c.bold, 8.52f, 38.64f, c.y, "Отпустил");
        signature(c, 81.72f, sx(243.48f), c.y, 2.96f, 0.64f, 7.68f, releasedBy);
        c.text(c.bold, 8.52f, sx(260.76f), c.y, "Получил");
        signature(c, sx(260.76f) + (303.84f - 260.76f), RIGHT, c.y, 2.96f, 0.64f, 7.68f, receivedBy);
    }

    /**
     * Строка подписи "Имя / ______": имя в начале линии, за ним косая черта, остаток линии —
     * место для подписи. Длинное имя уменьшается, чтобы под подпись оставалось не меньше 40% линии.
     */
    private void signature(Canvas c, float lineStart, float lineEnd, float baseline, float lineDrop, float lineWidth,
                           float size, String name) throws IOException {
        c.hline(lineStart, lineEnd, baseline - lineDrop, lineWidth, LINE_GRAY);
        String text = (name == null || name.isBlank() ? "" : name.trim() + " ") + "/";
        float maxWidth = (lineEnd - lineStart) * 0.6f;
        float fitted = Math.min(size, size * maxWidth / c.width(c.regular, size, text));
        c.text(c.regular, fitted, lineStart + 4, baseline - 0.24f, text);
    }

    /** Итог внизу: продажа, каждый возврат со знаком минус и "к оплате" — шрифтами и полями расходной накладной. */
    private void drawPayable(Canvas c, OneCInvoiceService.Invoice invoice) throws IOException {
        float left = 36.48f;
        float right = RIGHT;
        float amountRight = right - 1.62f;
        float size = 8.52f;
        float step = 14f;
        float height = 20 + 20 + step * (invoice.returns().size() + 1) + 14 + 20 + 12;
        c.y -= DOCUMENT_GAP;
        if (c.y - height < PAGE_BOTTOM) {
            c.newPage();
            c.y = PAGE_TOP;
        }
        c.text(c.bold, 11.88f, 39.36f, c.y, "Итог");
        c.y -= 4.36f;
        c.hline(left, right, c.y, 1.27f, LINE_GRAY);

        OneCInvoiceService.Document sale = invoice.sale();
        c.y -= 17.12f;
        c.text(c.regular, size, 38.40f, c.y,
                "Расходная накладная № " + sale.number() + " от " + formatDate(sale.date()));
        c.rightText(c.regular, size, amountRight, c.y, money(sale.total()));
        for (OneCInvoiceService.Document ret : invoice.returns()) {
            c.y -= step;
            c.text(c.regular, size, 38.40f, c.y,
                    "Возврат от покупателя № " + ret.number() + " от " + formatDate(ret.date()));
            c.rightText(c.regular, size, amountRight, c.y, "−" + money(ret.total()));
        }
        c.y -= 7;
        c.hline(left, right, c.y, 1.27f, LINE_GRAY);
        c.y -= 12.68f;
        BigDecimal payable = invoice.payable();
        c.text(c.bold, size, 38.40f, c.y, "Итого к оплате:");
        c.rightText(c.bold, size, amountRight, c.y, money(payable));
        c.y -= 19.56f;
        String count = "Сумма к оплате " + money(payable) + " теңге";
        c.text(c.regular, 7.68f, 38.28f, c.y, count);
        c.hline(38.28f, 38.28f + c.width(c.regular, 7.68f, count), c.y - 0.84f, 0.6f, 0f);
        c.y -= 11.28f;
        c.text(c.bold, size, 38.64f, c.y, NumberToWordsRu.amountInWords(payable));
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
