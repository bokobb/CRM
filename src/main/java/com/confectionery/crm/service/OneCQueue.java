package com.confectionery.crm.service;

import com.confectionery.crm.domain.Order;
import com.confectionery.crm.domain.ProductReturn;
import com.confectionery.crm.domain.ProductionRequest;
import com.confectionery.crm.domain.Purchase;
import com.confectionery.crm.domain.StockDocument;
import com.confectionery.crm.repository.OrderRepository;
import com.confectionery.crm.repository.ProductReturnRepository;
import com.confectionery.crm.repository.ProductionRequestRepository;
import com.confectionery.crm.repository.PurchaseRepository;
import com.confectionery.crm.repository.StockDocumentRepository;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Фоновая отправка в 1С: 1cFresh часто отвечает минутами, поэтому CRM больше не ждёт её в
 * момент нажатия кнопки. Изменяющий метод только помечает запись (заказ, заявку, закупку,
 * возврат, документ склада) флагом {@code oneCPending} — пользователь сразу получает ответ, а после коммита
 * один фоновый поток приводит документы 1С к тому, что сейчас в CRM (см. {@code reconcile} в
 * OneC*SyncService). Несколько правок подряд сливаются в одну отправку.
 *
 * <p>Всё общение с 1С, меняющее документы, идёт через этот один поток — в том числе автоповтор
 * и импорт реализаций из 1С, чтобы они не пересекались друг с другом. Флаг хранится в базе,
 * поэтому после перезапуска backend очередь продолжает с того же места (опрос раз в минуту).
 * Ошибки 1С, как и раньше, — в {@code oneCSyncError} и уведомлением админу.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OneCQueue {

    private final OneCClient oneCClient;
    private final TransactionTemplate tx;
    private final OrderRepository orderRepository;
    private final ProductionRequestRepository requestRepository;
    private final PurchaseRepository purchaseRepository;
    private final ProductReturnRepository returnRepository;
    private final StockDocumentRepository stockDocumentRepository;
    private final OneCOrderSyncService orderSync;
    private final OneCProductionSyncService productionSync;
    private final OneCPurchaseSyncService purchaseSync;
    private final OneCReturnSyncService returnSync;
    private final OneCStockDocumentSyncService stockDocumentSync;
    private final OneCOrderImportService orderImport;
    private final ApplicationEventPublisher events;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "onec-queue");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean drainQueued = new AtomicBoolean();

    /** Заказ отправлен в 1С (успешно или с ошибкой) — например, чтобы после отгрузки послать накладную. */
    public record OrderSynced(Long orderId) {
    }

    public void order(Order order) {
        order.setOneCPending(true);
        kickAfterCommit();
    }

    public void request(ProductionRequest request) {
        request.setOneCPending(true);
        kickAfterCommit();
    }

    public void purchase(Purchase purchase) {
        purchase.setOneCPending(true);
        kickAfterCommit();
    }

    public void productReturn(ProductReturn productReturn) {
        productReturn.setOneCPending(true);
        kickAfterCommit();
    }

    public void stockDocument(StockDocument document) {
        document.setOneCPending(true);
        kickAfterCommit();
    }

    private void kickAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    kick();
                }
            });
        } else {
            kick();
        }
    }

    /** Раз в минуту — подхватить то, что осталось после перезапуска. */
    @Scheduled(fixedDelayString = "${crm.onec.queue-poll-ms:60000}", initialDelay = 30000)
    public void kick() {
        if (!oneCClient.isConfigured()) return;
        if (drainQueued.compareAndSet(false, true)) {
            worker.submit(this::drain);
        }
    }

    /** Автоповтор того, что не ушло из-за недоступности 1С, — в том же потоке, что и очередь. */
    @Scheduled(cron = "${crm.onec.order-retry-cron:0 */10 7-20 * * *}", zone = "${crm.schedule-zone:GMT+05:00}")
    public void retryFailed() {
        if (!oneCClient.isConfigured()) return;
        worker.submit(() -> run("автоповтор заказов", orderSync::retryFailedImported));
        worker.submit(() -> run("автоповтор заявок", productionSync::retryUnavailable));
    }

    /** Импорт реализаций из 1С — тоже через очередь, чтобы не читать 1С посреди собственной отправки. */
    @Scheduled(cron = "${crm.onec.document-import-cron:0 */5 7-18 * * *}", zone = "${crm.schedule-zone:GMT+05:00}")
    public void importOrders() {
        if (!oneCClient.isConfigured()) return;
        worker.submit(() -> run("импорт заказов из 1С", orderImport::scheduledImport));
    }

    private void drain() {
        drainQueued.set(false);
        process("заказ", orderRepository::findOneCPendingIds, orderRepository::clearOneCPending, id -> {
            orderSync.reconcile(id);
            events.publishEvent(new OrderSynced(id));
        });
        process("заявка", requestRepository::findOneCPendingIds, requestRepository::clearOneCPending, productionSync::reconcile);
        process("закупка", purchaseRepository::findOneCPendingIds, purchaseRepository::clearOneCPending, purchaseSync::reconcile);
        process("возврат", returnRepository::findOneCPendingIds, returnRepository::clearOneCPending, returnSync::reconcile);
        process("документ склада", stockDocumentRepository::findOneCPendingIds, stockDocumentRepository::clearOneCPending, stockDocumentSync::reconcile);
    }

    private void process(String what, Supplier<List<Long>> pending, Function<Long, Integer> clear, Consumer<Long> reconcile) {
        List<Long> ids;
        try {
            ids = tx.execute(st -> pending.get());
        } catch (Exception e) {
            log.warn("Очередь 1С: не удалось прочитать ожидающие ({}): {}", what, e.getMessage());
            return;
        }
        for (Long id : ids) {
            // Флаг снимаем ДО отправки: если пока идёт отправка запись снова поменяют — флаг
            // встанет заново, и следующий проход отправит уже свежее состояние.
            tx.executeWithoutResult(st -> clear.apply(id));
            long started = System.currentTimeMillis();
            try {
                reconcile.accept(id);
                log.info("Очередь 1С: {} №{} отправлен(а) за {} с", what, id, (System.currentTimeMillis() - started) / 1000);
            } catch (Exception e) {
                log.warn("Очередь 1С: {} №{} — {}", what, id, e.getMessage());
            }
        }
    }

    private static void run(String what, Runnable job) {
        try {
            job.run();
        } catch (Exception e) {
            log.warn("Очередь 1С: {} не удался: {}", what, e.getMessage());
        }
    }

    @PreDestroy
    void stop() {
        worker.shutdownNow();
    }
}
