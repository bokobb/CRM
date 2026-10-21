package com.confectionery.crm.service;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;

/**
 * Отчёт админки в Excel: заголовок и период, таблица как на странице (шапка, строки, итог), примечания внизу.
 * types — вид столбца: text, int, qty (штуки), money (₸), pct (проценты как текст «+63 %»).
 */
@Component
public class ReportXlsx {

    public record Table(String title, String period, List<String> head, List<String> types, List<List<Object>> rows,
                        List<Object> foot, List<String> notes) {
    }

    public byte[] write(Table t) {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet ws = wb.createSheet(safe(t.title()));
            Font bold = wb.createFont();
            bold.setBold(true);
            Font big = wb.createFont();
            big.setBold(true);
            big.setFontHeightInPoints((short) 14);
            CellStyle title = wb.createCellStyle();
            title.setFont(big);
            CellStyle head = box(wb, IndexedColors.GREY_25_PERCENT, bold, HorizontalAlignment.CENTER);
            CellStyle text = box(wb, null, null, HorizontalAlignment.LEFT);
            CellStyle num = box(wb, null, null, HorizontalAlignment.RIGHT);
            num.setDataFormat(wb.createDataFormat().getFormat("# ##0.###"));
            CellStyle money = box(wb, null, null, HorizontalAlignment.RIGHT);
            money.setDataFormat(wb.createDataFormat().getFormat("# ##0"));
            CellStyle footText = box(wb, IndexedColors.GREY_25_PERCENT, bold, HorizontalAlignment.LEFT);
            CellStyle footNum = box(wb, IndexedColors.GREY_25_PERCENT, bold, HorizontalAlignment.RIGHT);
            footNum.setDataFormat(wb.createDataFormat().getFormat("# ##0.###"));
            CellStyle footMoney = box(wb, IndexedColors.GREY_25_PERCENT, bold, HorizontalAlignment.RIGHT);
            footMoney.setDataFormat(wb.createDataFormat().getFormat("# ##0"));

            int cols = t.head().size();
            Row r0 = ws.createRow(0);
            Cell c0 = r0.createCell(0);
            c0.setCellValue(t.title());
            c0.setCellStyle(title);
            if (cols > 1) ws.addMergedRegion(new CellRangeAddress(0, 0, 0, cols - 1));
            ws.createRow(1).createCell(0).setCellValue(t.period());

            Row h = ws.createRow(3);
            for (int i = 0; i < cols; i++) put(h, i, t.head().get(i), head);
            int ri = 4;
            for (List<Object> row : t.rows()) {
                Row x = ws.createRow(ri++);
                for (int i = 0; i < cols && i < row.size(); i++) {
                    String type = t.types().get(i);
                    put(x, i, row.get(i), type.equals("money") ? money : type.equals("text") || type.equals("pct") ? text : num);
                }
            }
            if (t.foot() != null) {
                Row x = ws.createRow(ri++);
                for (int i = 0; i < cols && i < t.foot().size(); i++) {
                    String type = t.types().get(i);
                    put(x, i, t.foot().get(i), type.equals("money") ? footMoney : type.equals("text") || type.equals("pct") ? footText : footNum);
                }
            }
            ri++;
            for (String n : t.notes() == null ? List.<String>of() : t.notes()) ws.createRow(ri++).createCell(0).setCellValue(n);
            for (int i = 0; i < cols; i++) ws.setColumnWidth(i, (i == 0 ? 40 : t.types().get(i).equals("text") ? 20 : 14) * 256);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Не получилось собрать Excel: " + e.getMessage(), e);
        }
    }

    private static String safe(String s) {
        String x = s.replaceAll("[\\\\/?*\\[\\]:]", " ");
        return x.length() > 31 ? x.substring(0, 31) : x;
    }

    private static void put(Row r, int i, Object v, CellStyle st) {
        Cell c = r.createCell(i);
        if (v instanceof BigDecimal b) c.setCellValue(b.doubleValue());
        else if (v instanceof Number n) c.setCellValue(n.doubleValue());
        else c.setCellValue(v == null ? "" : String.valueOf(v));
        c.setCellStyle(st);
    }

    private static CellStyle box(Workbook wb, IndexedColors fill, Font font, HorizontalAlignment align) {
        CellStyle s = wb.createCellStyle();
        s.setBorderTop(BorderStyle.THIN);
        s.setBorderBottom(BorderStyle.THIN);
        s.setBorderLeft(BorderStyle.THIN);
        s.setBorderRight(BorderStyle.THIN);
        s.setAlignment(align);
        s.setVerticalAlignment(VerticalAlignment.CENTER);
        if (fill != null) {
            s.setFillForegroundColor(fill.getIndex());
            s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        }
        if (font != null) s.setFont(font);
        return s;
    }
}
