package com.confectionery.crm.service;

import com.confectionery.crm.domain.Client;
import com.confectionery.crm.domain.Product;
import com.confectionery.crm.domain.ProductReturn;
import com.confectionery.crm.domain.ProductReturnItem;
import com.confectionery.crm.repository.ClientRepository;
import com.confectionery.crm.repository.ProductRepository;
import com.confectionery.crm.repository.ProductReturnRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Обратный импорт возвратов: бухгалтер заводит "Возврат товаров от покупателя" прямо в 1С —
 * этот сервис заводит по ним возвраты в CRM (см. {@link ProductReturn}), чтобы они были видны в
 * разделе "Возвраты" и вычитались из фактической продажи на дашборде. В отличие от импорта
 * реализаций (см. OneCOrderImportService) берутся ВСЕ документы, а не только сегодняшние — по
 * просьбе пользователя, их немного.
 *
 * <p>Пропускаются: созданные самой CRM (Комментарий "Возврат CRM №...", см. OneCReturnSyncService)
 * и помеченные на удаление. Дедупликация — по {@link ProductReturn#getOneCDocumentKey()}. Если
 * бухгалтер позже пометил на удаление документ, который мы когда-то импортировали, возврат
 * удаляется и из CRM (только импортированный — у созданных в CRM удаление идёт через CRM).
 * Клиент и товар сопоставляются по GUID/названию, отсутствующие в CRM — заводятся, как в
 * импорте реализаций. Возврат приходит незакреплённым — к заказу его закрепляют в CRM.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OneCReturnImportService {

    /** createdBy у импортированных возвратов — по нему отличаем их от созданных в CRM. */
    public static final String IMPORTED_BY = "1С";
    private static final String CRM_MARKER = "Возврат CRM №";
    private static final DateTimeFormatter ONEC_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final OneCClient oneCClient;
    private final ProductReturnRepository productReturnRepository;
    private final ClientRepository clientRepository;
    private final ProductRepository productRepository;
    private final UserActivityTracker userActivityTracker;

    @Value("${crm.onec.idle-skip-minutes:10}")
    private int idleSkipMinutes;

    /**
     * Каждые 5 минут — но только если пользователи недавно были активны (см.
     * {@link UserActivityTracker}, {@code crm.onec.idle-skip-minutes}): без них 1С не трогаем.
     */
    @Scheduled(cron = "${crm.onec.return-import-cron:0 */5 * * * *}", zone = "${crm.schedule-zone:GMT+05:00}")
    public void scheduledImport() {
        if (!oneCClient.isConfigured()) {
            return;
        }
        if (!userActivityTracker.recentlyActive(Duration.ofMinutes(idleSkipMinutes))) {
            log.debug("Пользователи неактивны более {} мин — пропускаю импорт возвратов из 1С", idleSkipMinutes);
            return;
        }
        try {
            ImportResult r = importAll();
            if (r.imported() > 0 || r.deleted() > 0 || !r.unmatched().isEmpty()) {
                log.info("Импорт возвратов из 1С: создано {}, удалено (помечены в 1С) {}, клиентов {}, товаров {}, не сопоставлено {} {}",
                        r.imported(), r.deleted(), r.clientsCreated(), r.productsCreated(), r.unmatched().size(), r.unmatched());
            }
        } catch (Exception e) {
            log.warn("Импорт возвратов из 1С не удался: {}", e.getMessage());
        }
    }

    @Transactional
    public ImportResult importAll() {
        List<OneCClient.SaleHeader> headers = oneCClient.fetchCustomerReturnHeaders();
        Map<String, List<OneCClient.SaleLine>> linesByParent = new HashMap<>();
        for (OneCClient.SaleLine line : oneCClient.fetchCustomerReturnLines()) {
            linesByParent.computeIfAbsent(line.parentRef(), k -> new ArrayList<>()).add(line);
        }
        Map<String, String> nomenclatureNames = null;
        Map<String, OneCClient.Contragent> contragentsByRef = null;
        Map<String, Product> productByName = new HashMap<>();
        for (Product p : productRepository.findAll()) {
            productByName.putIfAbsent(p.getName().trim().toLowerCase(), p);
        }

        int imported = 0, deleted = 0, clientsCreated = 0, productsCreated = 0;
        List<String> unmatched = new ArrayList<>();
        for (OneCClient.SaleHeader header : headers) {
            if (header.comment() != null && header.comment().startsWith(CRM_MARKER)) {
                continue;
            }
            ProductReturn existing = productReturnRepository.findByOneCDocumentKey(header.ref()).orElse(null);
            if (existing != null) {
                if (header.deletionMark() && IMPORTED_BY.equals(existing.getCreatedBy())) {
                    productReturnRepository.delete(existing);
                    deleted++;
                }
                continue;
            }
            if (header.deletionMark()) {
                continue;
            }
            List<OneCClient.SaleLine> docLines = linesByParent.getOrDefault(header.ref(), List.of());
            if (docLines.isEmpty()) {
                continue;
            }

            Client client = clientRepository.findByOneCContragentKey(header.counterpartyKey()).orElse(null);
            if (client == null) {
                if (contragentsByRef == null) {
                    contragentsByRef = new HashMap<>();
                    for (OneCClient.Contragent c : oneCClient.fetchContragents()) {
                        contragentsByRef.putIfAbsent(c.ref(), c);
                    }
                }
                OneCClient.Contragent contragent = contragentsByRef.get(header.counterpartyKey());
                if (contragent == null || contragent.description().isBlank()) {
                    unmatched.add("№" + header.number() + ": контрагент не найден в 1С");
                    continue;
                }
                client = new Client();
                client.setName(contragent.description().trim());
                client.setOneCContragentKey(contragent.ref());
                client.setOneCContractKey(contragent.contractKey());
                client = clientRepository.save(client);
                clientsCreated++;
            }

            ProductReturn productReturn = new ProductReturn();
            String unmatchedLine = null;
            for (OneCClient.SaleLine line : docLines) {
                Product product = productRepository.findByOneCNomenclatureKey(line.nomenclatureKey()).orElse(null);
                if (product == null) {
                    if (nomenclatureNames == null) {
                        nomenclatureNames = oneCClient.fetchNomenclatureNames();
                    }
                    String name = nomenclatureNames.get(line.nomenclatureKey());
                    if (name == null || name.isBlank()) {
                        unmatchedLine = line.nomenclatureKey();
                        break;
                    }
                    product = productByName.get(name.trim().toLowerCase());
                    if (product == null) {
                        product = new Product();
                        product.setName(name.trim());
                        product.setUnit("шт");
                        product.setPrice(line.price() != null ? line.price() : BigDecimal.ZERO);
                        productsCreated++;
                    }
                    product.setOneCNomenclatureKey(line.nomenclatureKey());
                    product = productRepository.save(product);
                    productByName.put(name.trim().toLowerCase(), product);
                }
                ProductReturnItem item = new ProductReturnItem();
                item.setProduct(product);
                item.setQuantity(line.quantity());
                item.setPrice(line.price() != null ? line.price() : BigDecimal.ZERO);
                productReturn.addItem(item);
            }
            if (unmatchedLine != null) {
                unmatched.add("№" + header.number() + ": номенклатура " + unmatchedLine + " не найдена в 1С");
                continue;
            }

            LocalDateTime date = parseDate(header.date());
            productReturn.setClient(client);
            productReturn.setReturnDate(date.toLocalDate());
            productReturn.setCreatedAt(date);
            productReturn.setCreatedBy(IMPORTED_BY);
            String comment = header.comment() == null ? "" : header.comment().trim();
            productReturn.setReason(comment.isEmpty() ? null : comment.length() > 255 ? comment.substring(0, 255) : comment);
            productReturn.setOneCDocumentKey(header.ref());
            productReturn.setOneCDocumentNumber(header.number());
            productReturnRepository.save(productReturn);
            imported++;
        }
        return new ImportResult(imported, deleted, clientsCreated, productsCreated, unmatched);
    }

    private LocalDateTime parseDate(String date) {
        try {
            return LocalDateTime.parse(date, ONEC_DATE);
        } catch (Exception e) {
            return LocalDateTime.now();
        }
    }

    public record ImportResult(int imported, int deleted, int clientsCreated, int productsCreated, List<String> unmatched) {
    }
}
