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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
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
 * сервис читает такие документы из 1С и заводит по ним обычные Заказы в CRM. Статус зависит от
 * {@link com.confectionery.crm.service.OneCClient.SaleHeader#posted()}: документ уже проведён —
 * Заказ сразу в статусе "Завершён" (см. {@link OrderStatus#COMPLETED}, {@code shippedAt} =
 * дата документа) — цикл сборки/отгрузки не нужен, товар и так уже физически ушёл со склада (сам
 * остаток CRM подтягивает отдельно, см. OneCStockSyncService, поэтому здесь остаток не трогаем —
 * только сохраняем сам факт продажи в истории CRM); документ ещё черновик (не проведён) — Заказ
 * заводится "Новым" (см. {@link OrderStatus#NEW}), без {@code shippedAt}, и дальше идёт по
 * обычному циклу сборки/отгрузки в CRM как любой другой заказ — {@code Order.oneCDocumentKey}
 * уже проставлен, поэтому при отгрузке {@link OneCOrderSyncService#trySyncOrder} допишет тот же
 * документ в 1С (а не создаст второй), и корректно откажется его трогать, если к тому моменту
 * бухгалтер уже успеет провести документ сам. Симметрично: если бухгалтер провёл документ в 1С
 * РАНЬШЕ, чем заказ дошёл до отгрузки в CRM (обычная ситуация — цикл сборки в CRM может занять
 * время) — этот же импорт при следующем прогоне (каждые 5 минут) увидит {@code Posted:true} у
 * уже существующего Заказа в статусе "Новый" и сам доведёт его до "Завершён" + проставит
 * {@code shippedAt}, не дожидаясь, пока кто-то в CRM пройдёт весь цикл сборки/отгрузки вручную —
 * товар по факту уже отгружен, раз бухгалтер провёл документ.
 *
 * <p>Дедупликация — двухслойная: 1) документы, созданные самой CRM (см. OneCOrderSyncService),
 * помечены в {@code Комментарий} строкой "Заказ CRM №..." — такие пропускаем всегда, даже уже
 * проведённые бухгалтером вручную (тот же путь довершения статуса их не касается — им занимается
 * обычный цикл CRM); 2) документ, по которому этот импорт уже когда-то создал Заказ — проверяем
 * {@link Order#getOneCDocumentKey()} через {@link OrderRepository#findByOneCDocumentKey}, и это
 * тот самый момент, где проверяется довершение статуса (см. выше), а не просто пропуск.
 * Контрагент и номенклатура сопоставляются по GUID; если в 1С такой контрагент/номенклатура
 * реально есть, а в CRM карточки ещё нет — заводим её сами (минимально: имя/название, GUID для
 * связи, цена товара — из самой строки документа), а не пропускаем документ с ошибкой. Раньше
 * несопоставленное просто копилось в отчёте с уведомлением ADMIN — на каждый перезапуск (каждые
 * 5 минут) это давало один и тот же спам, пока кто-то не сопоставит вручную; теперь чинится сразу
 * само. Документ пропускается ЦЕЛИКОМ только если по GUID контрагента/номенклатуры не нашлось
 * вообще ничего даже в самом 1С (не должно случаться в норме — тогда это уже проблема данных в
 * 1С, а не CRM) — такие редкие случаи по-прежнему уходят одним уведомлением ADMIN.
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
    private static final int SYNC_DAYS = 7;

    private final OneCClient oneCClient;
    private final OrderRepository orderRepository;
    private final ClientRepository clientRepository;
    private final ProductRepository productRepository;
    private final NotificationService notificationService;
    private final UserActivityTracker userActivityTracker;

    @Value("${crm.onec.idle-skip-minutes:10}")
    private int idleSkipMinutes;

    /**
     * Каждые 5 минут в рабочее время (7:00–18:55) — новые "Реализации", заведённые в 1С
     * напрямую, появляются в CRM почти сразу. Пропускается, если давно не было реальных
     * пользователей (см. {@link UserActivityTracker}, {@code crm.onec.idle-skip-minutes}) —
     * запрос к 1С медленный, гонять его без надобности не стоит.
     */
    @Scheduled(cron = "${crm.onec.document-import-cron:0 */5 7-18 * * *}", zone = "${crm.schedule-zone:GMT+05:00}")
    public void scheduledImport() {
        if (!oneCClient.isConfigured()) {
            return;
        }
        if (!userActivityTracker.recentlyActive(Duration.ofMinutes(idleSkipMinutes))) {
            log.debug("Пользователи неактивны более {} мин — пропускаю импорт заказов из 1С", idleSkipMinutes);
            return;
        }
        try {
            ImportResult result = importAll();
            if (result.imported() > 0 || result.completed() > 0 || result.clientsCreated() > 0 || result.productsCreated() > 0 || !result.unmatched().isEmpty()) {
                log.info("Импорт заказов из 1С: создано заказов {}, довершено (проведены в 1С) {}, клиентов {}, товаров {}, не сопоставлено {}",
                        result.imported(), result.completed(), result.clientsCreated(), result.productsCreated(), result.unmatched().size());
            }
            if (!result.unmatched().isEmpty()) {
                notificationService.notifyRole(Role.ADMIN,
                        "Импорт из 1С: не удалось сопоставить " + result.unmatched().size()
                                + " документ(ов) \"Реализация\" (нет даже в 1С) — " + String.join("; ", result.unmatched()), null);
            }
        } catch (Exception e) {
            log.warn("Импорт заказов из 1С не удался: {}", e.getMessage());
        }
    }

    @Transactional
    public ImportResult importAll() {
        // Последние 7 дней: новые документы заводятся только сегодняшние (см. ниже), а более
        // старые нужны, чтобы подтянуть правки бухгалтера в уже импортированных заказах.
        List<OneCClient.SaleHeader> headers = oneCClient.fetchSaleHeaders(LocalDate.now().minusDays(SYNC_DAYS));
        List<String> refs = headers.stream()
                .filter(h -> !h.deletionMark() && (h.comment() == null || !h.comment().startsWith(CRM_MARKER)))
                .map(OneCClient.SaleHeader::ref).toList();
        List<OneCClient.SaleLine> lines = refs.isEmpty() ? List.of() : oneCClient.fetchSaleLines(refs);
        Map<String, List<OneCClient.SaleLine>> linesByParent = new HashMap<>();
        for (OneCClient.SaleLine line : lines) {
            linesByParent.computeIfAbsent(line.parentRef(), k -> new ArrayList<>()).add(line);
        }
        Map<String, String> nomenclatureNames = oneCClient.fetchNomenclatureNames();
        Map<String, Product> productByName = new HashMap<>();
        for (Product p : productRepository.findAll()) {
            productByName.putIfAbsent(p.getName().trim().toLowerCase(), p);
        }
        Map<String, OneCClient.Contragent> contragentsByRef = new HashMap<>();
        for (OneCClient.Contragent c : oneCClient.fetchContragents()) {
            contragentsByRef.putIfAbsent(c.ref(), c);
        }

        int imported = 0;
        int updated = 0;
        int completed = 0;
        int clientsCreated = 0;
        int productsCreated = 0;
        List<String> unmatched = new ArrayList<>();
        for (OneCClient.SaleHeader header : headers) {
            if (header.comment() != null && header.comment().startsWith(CRM_MARKER)) {
                continue;
            }
            // Помеченные на удаление — в т.ч. документы заказов, удалённых в CRM (см.
            // OrderService.delete): иначе удалённый заказ вернулся бы следующим прогоном.
            if (header.deletionMark()) {
                continue;
            }
            Order existing = orderRepository.findByOneCDocumentKey(header.ref()).orElse(null);
            if (existing != null) {
                // Заводили Заказом "Новый", т.к. документ ещё не был проведён (см. ниже) —
                // бухгалтер мог провести его в 1С позже, вручную; при следующем прогоне (каждые
                // 5 минут) видим это и довершаем статус, не дожидаясь, пока кто-то в CRM вручную
                // проведёт заказ через обычную сборку/отгрузку — товар и так уже фактически ушёл.
                if (header.posted() && existing.getStatus() == OrderStatus.NEW) {
                    existing.setStatus(OrderStatus.COMPLETED);
                    existing.setShippedAt(parseDate(header.date()));
                    orderRepository.save(existing);
                    completed++;
                }
                if (applyOneCChanges(existing, linesByParent.getOrDefault(header.ref(), List.of()),
                        nomenclatureNames, productByName)) {
                    updated++;
                }
                continue;
            }
            if (!parseDate(header.date()).toLocalDate().isEqual(LocalDate.now())) {
                continue;
            }
            Client client = clientRepository.findByOneCContragentKey(header.counterpartyKey()).orElse(null);
            if (client == null) {
                OneCClient.Contragent contragent = contragentsByRef.get(header.counterpartyKey());
                if (contragent == null || contragent.description().isBlank()) {
                    unmatched.add("№" + header.number() + ": контрагент " + header.counterpartyKey() + " не найден в 1С");
                    continue;
                }
                client = new Client();
                client.setName(contragent.description().trim());
                client.setOneCContragentKey(contragent.ref());
                client.setOneCContractKey(contragent.contractKey());
                client = clientRepository.save(client);
                clientsCreated++;
            }
            List<OneCClient.SaleLine> docLines = linesByParent.getOrDefault(header.ref(), List.of());
            if (docLines.isEmpty()) {
                continue;
            }
            List<OrderItem> items = new ArrayList<>();
            String unmatchedLine = null;
            for (OneCClient.SaleLine line : docLines) {
                Product product = productRepository.findByOneCNomenclatureKey(line.nomenclatureKey()).orElse(null);
                if (product == null) {
                    product = resolveProductByName(line.nomenclatureKey(), nomenclatureNames, productByName);
                }
                if (product == null) {
                    String name = nomenclatureNames.get(line.nomenclatureKey());
                    if (name == null || name.isBlank()) {
                        unmatchedLine = line.nomenclatureKey() + " (нет в 1С)";
                        break;
                    }
                    product = new Product();
                    product.setName(name.trim());
                    product.setUnit("шт");
                    product.setPrice(line.price() != null ? line.price() : BigDecimal.ZERO);
                    product.setOneCNomenclatureKey(line.nomenclatureKey());
                    product = productRepository.save(product);
                    productByName.put(name.trim().toLowerCase(), product);
                    productsCreated++;
                }
                OrderItem item = new OrderItem();
                item.setProduct(product);
                item.setQuantity(line.quantity());
                item.setPrice(line.price());
                items.add(item);
            }
            if (unmatchedLine != null) {
                unmatched.add("№" + header.number() + ": номенклатура «" + unmatchedLine + "»");
                continue;
            }

            LocalDateTime date = parseDate(header.date());
            Order order = new Order();
            order.setClient(client);
            if (header.posted()) {
                order.setStatus(OrderStatus.COMPLETED);
                order.setShippedAt(date);
            } else {
                order.setStatus(OrderStatus.NEW);
            }
            order.setCreatedAt(date);
            order.setNotes("Импортировано из 1С, документ №" + header.number());
            order.setOneCDocumentKey(header.ref());
            order.setOneCDocumentNumber(header.number());
            order.setImportedFromOneC(true);
            items.forEach(order::addItem);
            orderRepository.save(order);
            imported++;
        }
        if (updated > 0) {
            log.info("Импорт из 1С: в {} заказ(ах) состав обновлён по 1С", updated);
        }
        return new ImportResult(imported, completed, clientsCreated, productsCreated, unmatched);
    }

    /**
     * Бухгалтер поменял в 1С состав документа, по которому уже заведён заказ, — заказ в CRM
     * становится таким же (количество, цены, позиции). Только если у заказа нет ошибки выгрузки:
     * ошибка значит, что в CRM есть правка, ещё не дошедшая до 1С, — тогда её дотолкнёт
     * автоповтор (OneCOrderSyncService), а 1С не перетирает CRM. Если отгрузка шла через CRM
     * (остаток уже списан), остаток склада ГП двигается на разницу.
     */
    private boolean applyOneCChanges(Order order, List<OneCClient.SaleLine> docLines,
                                     Map<String, String> nomenclatureNames, Map<String, Product> productByName) {
        if (!order.isImportedFromOneC() || order.getStatus() == OrderStatus.CANCELLED
                || order.getOneCSyncError() != null || docLines.isEmpty()) {
            return false;
        }
        Map<String, BigDecimal> inDoc = new HashMap<>();
        for (OneCClient.SaleLine line : docLines) {
            inDoc.merge(line.nomenclatureKey() + "|" + line.price().stripTrailingZeros().toPlainString(),
                    line.quantity(), BigDecimal::add);
        }
        Map<String, BigDecimal> inCrm = new HashMap<>();
        for (OrderItem item : order.getItems()) {
            String key = item.getProduct().getOneCNomenclatureKey();
            if (key == null) return false;
            inCrm.merge(key + "|" + item.getPrice().stripTrailingZeros().toPlainString(), item.getQuantity(), BigDecimal::add);
        }
        if (inDoc.keySet().equals(inCrm.keySet())
                && inDoc.entrySet().stream().allMatch(e -> e.getValue().compareTo(inCrm.get(e.getKey())) == 0)) {
            return false;
        }
        List<OrderItem> newItems = new ArrayList<>();
        for (OneCClient.SaleLine line : docLines) {
            Product product = productRepository.findByOneCNomenclatureKey(line.nomenclatureKey()).orElse(null);
            if (product == null) {
                product = resolveProductByName(line.nomenclatureKey(), nomenclatureNames, productByName);
            }
            if (product == null) {
                log.warn("Заказ №{}: номенклатура {} из 1С не найдена в CRM — состав не обновлён", order.getId(), line.nomenclatureKey());
                return false;
            }
            OrderItem item = new OrderItem();
            item.setProduct(product);
            item.setQuantity(line.quantity());
            item.setPrice(line.price());
            newItems.add(item);
        }
        boolean stockWrittenOff = order.getVehicleNumber() != null
                && (order.getStatus() == OrderStatus.DELIVERED || order.getStatus() == OrderStatus.COMPLETED);
        if (stockWrittenOff) {
            for (OrderItem old : order.getItems()) {
                Product p = old.getProduct();
                p.setQuantityInStock(p.getQuantityInStock().add(old.getQuantity()));
            }
            for (OrderItem item : newItems) {
                Product p = item.getProduct();
                p.setQuantityInStock(p.getQuantityInStock().subtract(item.getQuantity()));
            }
        }
        order.getItems().clear();
        newItems.forEach(order::addItem);
        orderRepository.save(order);
        log.info("Заказ №{} — состав обновлён по документу №{} из 1С", order.getId(), order.getOneCDocumentNumber());
        return true;
    }

    /** Товар уже был в CRM, но без привязки к 1С — ищем по точному названию номенклатуры и кэшируем GUID на будущее. */
    private Product resolveProductByName(String nomenclatureKey, Map<String, String> nomenclatureNames, Map<String, Product> productByName) {
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

    public record ImportResult(int imported, int completed, int clientsCreated, int productsCreated, List<String> unmatched) {
    }
}
