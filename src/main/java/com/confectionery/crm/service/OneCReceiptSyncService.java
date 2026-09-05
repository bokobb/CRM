package com.confectionery.crm.service;

import com.confectionery.crm.domain.Product;
import com.confectionery.crm.domain.Role;
import com.confectionery.crm.domain.StockMovement;
import com.confectionery.crm.domain.StockMovementType;
import com.confectionery.crm.repository.ProductRepository;
import com.confectionery.crm.repository.StockMovementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "Общее оприходование" — раз в день все РУЧНЫЕ поступления готовой продукции на склад ГП
 * (зав. складом сам добавил через "Поступления", см. StockMovement.manual), которые ещё не
 * выгружены в 1С, собираются в ОДИН документ "Оприходование товаров" и сразу проводятся
 * (в отличие от "Реализации" по заказам — здесь черновик бухгалтеру не нужен). Дата документа
 * всегда 06:00 того дня, за который собран приход, независимо от фактического времени запуска.
 *
 * <p><b>Приход от заявок на производство сюда не попадает</b> — в 1С для него есть свой
 * правильный документ ("Отчёт производства за смену" + связанная "Требование-накладная" на
 * списание сырья), см. {@link OneCProductionSyncService}. "Оприходование товаров" в 1С
 * реально используется только для ручных корректировок остатков без производства (найденный
 * товар и т.п.) — этому сервису здесь и место.
 *
 * <p>Запускается двумя путями: вручную кнопкой "Общее оприходование" на странице
 * "Поступления" (см. web/mobile), и автоматически раз в сутки в 20:00 ({@link
 * #scheduledPostDailyReceipts}) — на случай, если зав. складом забыл нажать кнопку сам. Оба
 * пути вызывают один и тот же {@link #postDailyReceipts}, который просто берёт все ещё не
 * выгруженные движения за день — если кнопку уже нажимали и всё выгрузили, повторный запуск
 * (в т.ч. из расписания) найдёт 0 движений и ничего не сделает.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OneCReceiptSyncService {

    private static final DateTimeFormatter ONEC_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final LocalTime RECEIPT_TIME = LocalTime.of(6, 0);

    private final OneCClient oneCClient;
    private final StockMovementRepository stockMovementRepository;
    private final ProductRepository productRepository;
    private final NotificationService notificationService;

    @Value("${crm.onec.organization-key:}")
    private String organizationKey;
    @Value("${crm.onec.tax-accounting-type-key:}")
    private String taxAccountingTypeKey;
    @Value("${crm.onec.finished-goods-warehouse-key:d2791a64-4f43-11f1-9670-5254007c5efd}")
    private String warehouseKey;
    @Value("${crm.onec.receipt-price-type-key:9a6a136a-e4a6-11f0-8ef7-5254007c5efd}")
    private String receiptPriceTypeKey;

    /** Автоматический прогон за СЕГОДНЯ в 20:00 — на случай, если зав. складом забыл нажать кнопку сам. */
    @Scheduled(cron = "${crm.onec.post-cron:0 0 20 * * *}")
    public void scheduledPostDailyReceipts() {
        if (!oneCClient.isConfigured()) {
            return;
        }
        try {
            PostReceiptsResult result = postDailyReceipts(LocalDate.now());
            if (result.movementsCount() > 0 || !result.unresolved().isEmpty()) {
                log.info("Автооприходование в 1С за {}: позиций {}, документ №{}, не сопоставлено: {}",
                        LocalDate.now(), result.movementsCount(), result.documentNumber(), result.unresolved());
            }
        } catch (Exception e) {
            log.warn("Не удалось выполнить автооприходование в 1С: {}", e.getMessage());
        }
    }

    /**
     * Вызывается и кнопкой "Общее оприходование", и расписанием выше. Берёт все ещё не
     * выгруженные в 1С поступления товара (StockMovement RECEIPT) за указанный день,
     * группирует по товару (суммирует количество) и создаёт один проведённый документ
     * "Оприходование товаров" на 06:00 этого дня. Товары, для которых не подобралась
     * номенклатура/шаблон счетов, остаются невыгруженными — попадут в следующий прогон, когда
     * сопоставление появится; из-за одного такого товара весь день не блокируется.
     */
    @Transactional
    public PostReceiptsResult postDailyReceipts(LocalDate date) {
        if (!oneCClient.isConfigured()) {
            throw new IllegalStateException("Подключение к 1С не настроено (crm.onec.base-url/username/password)");
        }
        LocalDateTime start = date.atStartOfDay();
        LocalDateTime end = start.plusDays(1);
        List<StockMovement> pending = stockMovementRepository
                .findByTypeAndManualAndProductIsNotNullAndOneCSyncedAtIsNullAndCreatedAtBetween(
                        StockMovementType.RECEIPT, true, start, end);
        if (pending.isEmpty()) {
            return new PostReceiptsResult(0, null, List.of());
        }

        Map<Product, BigDecimal> totals = new LinkedHashMap<>();
        Map<Product, List<StockMovement>> movementsByProduct = new LinkedHashMap<>();
        for (StockMovement m : pending) {
            totals.merge(m.getProduct(), m.getQuantity(), BigDecimal::add);
            movementsByProduct.computeIfAbsent(m.getProduct(), p -> new ArrayList<>()).add(m);
        }

        List<Map<String, Object>> lines = new ArrayList<>();
        List<StockMovement> included = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        int lineNumber = 1;
        for (var entry : totals.entrySet()) {
            Product product = entry.getKey();
            Map<String, Object> template = resolveReceiptLineTemplate(product);
            if (template == null) {
                unresolved.add(product.getName());
                continue;
            }
            Map<String, Object> line = new LinkedHashMap<>(template);
            line.put("LineNumber", String.valueOf(lineNumber++));
            line.put("Номенклатура_Key", product.getOneCNomenclatureKey());
            line.put("Количество", entry.getValue());
            line.put("Цена", BigDecimal.ZERO);
            line.put("Сумма", BigDecimal.ZERO);
            lines.add(line);
            included.addAll(movementsByProduct.get(product));
        }

        if (!unresolved.isEmpty()) {
            notificationService.notifyRole(Role.ADMIN,
                    "Оприходование в 1С за " + date + ": не сопоставлено с 1С (нет истории/группы номенклатуры) — "
                            + String.join(", ", unresolved), null);
        }
        if (lines.isEmpty()) {
            return new PostReceiptsResult(0, null, unresolved);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("Date", ONEC_DATE.format(LocalDateTime.of(date, RECEIPT_TIME)));
        body.put("Posted", true);
        body.put("Организация_Key", organizationKey);
        body.put("Склад_Key", warehouseKey);
        body.put("ВидУчетаНУ_Key", taxAccountingTypeKey);
        body.put("ТипЦен_Key", receiptPriceTypeKey);
        body.put("Комментарий", "Общее оприходование ГП за " + date);
        body.put("Товары", lines);

        OneCClient.CreatedDocument created = oneCClient.createReceiptDocument(body);

        LocalDateTime now = LocalDateTime.now();
        for (StockMovement m : included) {
            m.setOneCSyncedAt(now);
            m.setOneCDocumentNumber(created.number());
        }
        stockMovementRepository.saveAll(included);
        log.info("Оприходование ГП за {} выгружено в 1С проведённым документом №{} ({} позиций)",
                date, created.number(), lines.size());
        return new PostReceiptsResult(included.size(), created.number(), unresolved);
    }

    /**
     * Номенклатура + шаблон счетов кэшируются на товаре (Product.oneCReceiptTemplate) —
     * дальше не обращаемся в 1С за этими данными повторно. Общий подбор номенклатуры по
     * названию переиспользуется из OneCOrderSyncService не был вынесен отдельно намеренно —
     * два независимых, самодостаточных сервиса проще читать по отдельности, чем городить
     * общий помощник ради десятка строк.
     */
    private Map<String, Object> resolveReceiptLineTemplate(Product product) {
        boolean changed = false;
        if (product.getOneCNomenclatureKey() == null) {
            String key = findNomenclatureKeyByName(product.getName());
            if (key == null) return null;
            product.setOneCNomenclatureKey(key);
            changed = true;
        }
        Map<String, Object> template;
        if (product.getOneCReceiptTemplate() == null) {
            Map<String, Object> seed = oneCClient.findAnyReceiptLineTemplate();
            String groupKey = oneCClient.fetchNomenclatureGroup(product.getOneCNomenclatureKey());
            if (seed == null || groupKey == null) return null;
            template = new LinkedHashMap<>(seed);
            template.put("КорСубконтоОприходованияБУ2", groupKey);
            template.put("КорСубконтоОприходованияНУ2", product.getOneCNomenclatureKey());
            product.setOneCReceiptTemplate(serializeTemplate(template));
            changed = true;
        } else {
            template = deserializeTemplate(product.getOneCReceiptTemplate());
        }
        if (changed) productRepository.save(product);
        return template;
    }

    private String findNomenclatureKeyByName(String name) {
        String needle = name.trim().toLowerCase();
        return oneCClient.fetchNomenclatureNames().entrySet().stream()
                .filter(e -> e.getValue().trim().toLowerCase().equals(needle))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    // Тот же простой формат "ключ=значение;...", что и Product.oneCLineTemplate — см. комментарий
    // в OneCOrderSyncService.serializeTemplate, здесь ровно та же логика и те же ограничения.
    private static String serializeTemplate(Map<String, Object> template) {
        StringBuilder sb = new StringBuilder();
        template.forEach((k, v) -> {
            if (!sb.isEmpty()) sb.append(';');
            sb.append(k).append('=').append(v == null ? "" : v);
        });
        return sb.toString();
    }

    private static Map<String, Object> deserializeTemplate(String raw) {
        Map<String, Object> template = new LinkedHashMap<>();
        for (String pair : raw.split(";")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            String key = pair.substring(0, eq);
            String value = pair.substring(eq + 1);
            template.put(key, switch (value) {
                case "true" -> Boolean.TRUE;
                case "false" -> Boolean.FALSE;
                case "" -> null;
                default -> value;
            });
        }
        return template;
    }

    /** documentNumber — null, если оприходовывать было нечего (0 позиций, движений или все не сопоставлены). */
    public record PostReceiptsResult(int movementsCount, String documentNumber, List<String> unresolved) {
    }
}
