package com.confectionery.crm.api.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Универсальный отчёт (меню «Отчёты»): карточки-итоги сверху и одна таблица. Приложение рисует
 * любой отчёт одним экраном, по типам колонок — поэтому новый отчёт не требует обновления APK.
 * Значения ячеек — числа или строки; type колонки: TEXT, MONEY (₸), QTY (количество), INT.
 * tone строки/карточки: null, "good" (зелёный), "bad" (красный), "muted" (серый).
 */
public record ReportDto(String key, String title, LocalDate from, LocalDate to, List<Card> cards,
                        List<Column> columns, List<Row> rows, List<Object> totals, List<String> notes) {

    public record Card(String label, Object value, String type, String hint, String tone) {
    }

    public record Column(String label, String type) {
    }

    public record Row(List<Object> cells, String tone) {
    }

    /** Пункт списка отчётов: period — DAY (один день) или PERIOD (с–по); defaultRange — TODAY/MONTH. */
    public record Info(String key, String title, String description, String period, String defaultRange) {
    }
}
