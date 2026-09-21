package com.confectionery.crm.service;

import com.confectionery.crm.domain.Client;
import com.confectionery.crm.domain.Order;
import com.confectionery.crm.domain.OrderItem;
import com.confectionery.crm.domain.OrderStatus;
import com.confectionery.crm.domain.Product;
import com.confectionery.crm.domain.Role;
import com.confectionery.crm.repository.ClientRepository;
import com.confectionery.crm.repository.OrderRepository;
import com.confectionery.crm.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Обратный импорт: бухгалтер иногда заводит "Реализацию товаров и услуг" ПРЯМО в 1С, минуя CRM
 * (например, разовая продажа не через обычный процесс заказа) — тогда CRM о ней не знает. Этот
 * сервис читает такие документы из 1С и заводит по ним обычные Заказы в CRM, сразу в статусе
 * "Завершён" (см. {@link OrderStatus#COMPLETED}) — цикл сборки/отгрузки не нужен, товар и так
 * уже физически ушёл со склада (сам остаток CRM подтягивает отдельно, см. OneCStockSyncService,
 * поэтому здесь остаток не трогаем — только сохраняем сам факт продажи в истории CRM).
 *
 * <p>Дедупликация — двухслойная: 1) документы, созданные самой CRM (см. OneCOrderSyncService),
 * помечены в {@code Комментарий} строкой "Заказ CRM №..." — такие пропускаем всегда, даже уже
 * проведённые бухгалтером вручную; 2) на случай, если этот импорт уже создал Заказ по документу
 * раньше — проверяем {@link Order#getOneCDocumentKey()} через {@link OrderRepository#findByOneCDocumentKey}.
 * Контрагент и номенклатура сопоставляются по GUID (заранее привязанным через
 * OneCClientLinkService/название) — документ, где хоть что-то не сопоставлено, пропускается
 * ЦЕЛИКОМ (частично импортированный заказ хуже отсутствующего), причина копится в отчёте и уходит
 * одним уведомлением ADMIN.
 *
 * <p>Импортируются только документы с датой = СЕГОДНЯ (по серверному времени) — заявка на весь
 * накопленный за годы список "Реализаций" 1С сразу создала бы кучу заказов задним числом; более
 * старые документы, если понадобится их занести, — вручную. Так как задача перезапускается
 * каждые 5 минут (см. {@link #scheduledImport()}), в течение дня документ всё равно подхватится.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OneCOrderImportService {

    private static final DateTimeFormatter ONEC_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final String CRM_MARKER = "Заказ CRM №";

    private final OneCClient oneCClient;
    private final OrderRepository orderRepository;
    private final ClientRepository clientRepository;
    private final ProductRepository productRepository;
    private final NotificationService notificationService;

    /** Каждые 5 минут в рабочее время (7:00–18:55) — новые "Реализации", заведённые в 1С напрямую, появляются в CRM почти сразу. */
    @Scheduled(cron = "${crm.onec.document-import-cron:0 */5 7-18 * * *}")
    public void scheduledImport() {
        if (!oneCClient.isConfigured()) {
            return;
        }
        try {
            ImportResult result = importAll();
            if (result.imported() > 0 || !result.unmatched().isEmpty()) {
                log.info("Импорт заказов из 1С: создано {}, не сопоставлено {}", result.imported(), result.unmatched().size());
            }
            if (result.imported() > 0) {
                notificationService.notifyRole(Role.ADMIN,
                        "Импорт из 1С: создано заказов из документов \"Реализация\" — " + result.imported(), null);
            }
            if (!result.unmatched().isEmpty()) {
                notificationService.notifyRole(Role.ADMIN,
                        "Импорт из 1С: не удалось сопоставить " + result.unmatched().size()
                                + " документ(ов) \"Реализация\" — " + String.join("; ", result.unmatched()), null);
            }
        } catch (Exception e) {
            log.warn("Импорт заказов из 1С не удался: {}", e.getMessage());
        }
    }

    @Transactional
    public ImportResult importAll() {
        List<OneCClient.SaleHeader> headers = oneCClient.fetchSaleHeaders();
        List<OneCClient.SaleLine> lines = oneCClient.fetchSaleLines();
        Map<String, List<OneCClient.SaleLine>> linesByParent = new HashMap<>();
        for (OneCClient.SaleLine line : lines) {
            linesByParent.computeIfAbsent(line.parentRef(), k -> new ArrayList<>()).add(line);
        }
        Map<String, String> nomenclatureNames = oneCClient.fetchNomenclatureNames();
        Map<String, Product> productByName = new HashMap<>();
        for (Product p : productRepository.findAll()) {
            productByName.putIfAbsent(p.getName().trim().toLowerCase(), p);
        }

        int imported = 0;
        List<String> unmatched = new ArrayList<>();
        for (OneCClient.SaleHeader header : headers) {
            if (header.comment() != null && header.comment().startsWith(CRM_MARKER)) {
                continue;
            }
            if (orderRepository.findByOneCDocumentKey(header.ref()).isPresent()) {
                continue;
            }
            if (!parseDate(header.date()).toLocalDate().isEqual(LocalDate.now())) {
                continue;
            }
            Client client = clientRepository.findByOneCContragentKey(header.counterpartyKey()).orElse(null);
            if (client == null) {
                unmatched.add("№" + header.number() + ": контрагент не привязан к клиенту CRM");
                continue;
            }
            List<OneCClient.SaleLine> docLines = linesByParent.getOrDefault(header.ref(), List.of());
            if (docLines.isEmpty()) {
                continue;
            }
            List<OrderItem> items = new ArrayList<>();
            String unmatchedLine = null;
            for (OneCClient.SaleLine line : docLines) {
                Product product = resolveProduct(line.nomenclatureKey(), nomenclatureNames, productByName);
                if (product == null) {
                    unmatchedLine = nomenclatureNames.getOrDefault(line.nomenclatureKey(), line.nomenclatureKey());
                    break;
                }
                OrderItem item = new OrderItem();
                item.setProduct(product);
                item.setQuantity(line.quantity());
                item.setPrice(line.price());
                items.add(item);
            }
            if (unmatchedLine != null) {
                unmatched.add("№" + header.number() + ": товар «" + unmatchedLine + "» не найден в CRM");
                continue;
            }

            LocalDateTime date = parseDate(header.date());
            Order order = new Order();
            order.setClient(client);
            order.setStatus(OrderStatus.COMPLETED);
            order.setCreatedAt(date);
            order.setShippedAt(date);
            order.setNotes("Импортировано из 1С, документ №" + header.number());
            order.setOneCDocumentKey(header.ref());
            order.setOneCDocumentNumber(header.number());
            items.forEach(order::addItem);
            orderRepository.save(order);
            imported++;
        }
        return new ImportResult(imported, unmatched);
    }

    private Product resolveProduct(String nomenclatureKey, Map<String, String> nomenclatureNames, Map<String, Product> productByName) {
        Product byKey = productRepository.findByOneCNomenclatureKey(nomenclatureKey).orElse(null);
        if (byKey != null) {
            return byKey;
        }
        String name = nomenclatureNames.get(nomenclatureKey);
        if (name == null) {
            return null;
        }
        Product byName = productByName.get(name.trim().toLowerCase());
        if (byName != null) {
            byName.setOneCNomenclatureKey(nomenclatureKey);
            productRepository.save(byName);
        }
        return byName;
    }

    private LocalDateTime parseDate(String date) {
        try {
            return LocalDateTime.parse(date, ONEC_DATE);
        } catch (Exception e) {
            return LocalDateTime.now();
        }
    }

    public record ImportResult(int imported, List<String> unmatched) {
    }
}
