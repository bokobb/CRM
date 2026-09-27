package com.confectionery.crm.service;

import com.confectionery.crm.domain.Order;
import com.confectionery.crm.domain.OrderItem;
import com.confectionery.crm.domain.ProductReturn;
import com.confectionery.crm.domain.ProductReturnItem;
import com.confectionery.crm.domain.Purchase;
import com.confectionery.crm.domain.PurchaseItem;
import com.confectionery.crm.domain.Role;
import com.confectionery.crm.domain.User;
import com.confectionery.crm.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Данные для накладной клиенту — берутся из самих документов 1С ("Реализация" заказа и
 * "Возврат товаров от покупателя" по каждому закреплённому за заказом возврату): номер, дата,
 * наименования, коды номенклатуры, количество и суммы — ровно то, что напечатала бы 1С.
 * Печатную форму 1С по OData получить нельзя (проверено 26.09: в 1С:Fresh нет ни HTTP-сервисов,
 * ни печати через стандартные веб-сервисы), поэтому саму вёрстку повторяет InvoicePdfService.
 * Если документа в 1С ещё нет или 1С недоступна — этот документ собирается из данных CRM,
 * чтобы накладная уходила клиенту в любом случае.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OneCInvoiceService {

    private static final String SALE = "Document_РеализацияТоваровУслуг";
    private static final String RETURN = "Document_ВозвратТоваровОтПокупателя";
    private static final String PURCHASE = "Document_ПоступлениеТоваровУслуг";
    private static final String DEFAULT_SUPPLIER = "Юг Сладости";
    private static final String DEFAULT_CONTRACT = "Без договора";

    private final OneCClient oneCClient;
    private final CompanyProfileService companyProfileService;
    private final UserRepository userRepository;
    @Value("${crm.invoice.warehouse-manager:Пайзахматов Бехруз}")
    private String defaultWarehouseManager;

    public Invoice load(Order order) {
        Document sale = fromOneC(SALE, order.getOneCDocumentKey(), "заказу №" + order.getId(), false, true);
        if (sale == null) {
            sale = saleFromCrm(order);
        }
        List<Document> returns = new ArrayList<>();
        order.getReturns().stream()
                .sorted(Comparator.comparing(ProductReturn::getReturnDate))
                .forEach(r -> {
                    Document doc = fromOneC(RETURN, r.getOneCDocumentKey(), "возврату №" + r.getId(), true, false);
                    returns.add(doc != null ? doc : returnFromCrm(r));
                });
        return new Invoice(sale, returns, order.getClient().getName().trim(), warehouseManager());
    }

    /**
     * Приходная накладная по закупке — из документа 1С "Поступление товаров и услуг" (как печатает
     * 1С: поставщик — контрагент, покупатель — мы, склад в шапке); до выгрузки в 1С — из данных CRM.
     */
    public Document loadPurchase(Purchase purchase) {
        Document doc = fromOneC(PURCHASE, purchase.getOneCDocumentKey(), "закупке №" + purchase.getId(), true, true);
        return doc != null ? doc : purchaseFromCrm(purchase);
    }

    /**
     * contragentIsSupplier — в шапке поставщик контрагент, а покупатель мы (возврат, поступление);
     * withWarehouse — печатать строку "Склад" (в возврате 1С её не печатает).
     */
    private Document fromOneC(String entitySet, String ref, String what, boolean contragentIsSupplier,
                              boolean withWarehouse) {
        if (!oneCClient.isConfigured() || ref == null) {
            return null;
        }
        try {
            OneCClient.PrintableDocument doc = oneCClient.fetchPrintableDocument(entitySet, ref);
            List<Line> lines = merge(doc.lines().stream()
                    .map(l -> new Line(l.code(), l.name(), l.quantity(), unitOrDefault(l.unit()), l.price(), l.sum()))
                    .toList());
            String contract = doc.contract().isBlank() ? DEFAULT_CONTRACT : doc.contract();
            String warehouse = withWarehouse ? doc.warehouse() : null;
            return contragentIsSupplier
                    ? new Document(printNumber(doc.number()), doc.date(), doc.contragent(), doc.organization(),
                            contract, warehouse, lines)
                    : new Document(printNumber(doc.number()), doc.date(), doc.organization(), doc.contragent(),
                            contract, warehouse, lines);
        } catch (Exception e) {
            log.warn("Не удалось получить из 1С документ для накладной по {}: {}", what, e.getMessage());
            return null;
        }
    }

    private Document saleFromCrm(Order order) {
        List<Line> lines = new ArrayList<>();
        for (OrderItem item : order.getItems()) {
            lines.add(new Line("", item.getProduct().getName(), item.getQuantity(),
                    unitOrDefault(item.getProduct().getUnit()), item.getPrice(),
                    item.getPrice().multiply(item.getQuantity())));
        }
        LocalDate date = order.getShippedAt() != null ? order.getShippedAt().toLocalDate() : LocalDate.now();
        String number = order.getOneCDocumentNumber() != null
                ? printNumber(order.getOneCDocumentNumber()) : String.valueOf(order.getId());
        return new Document(number, date, supplierName(), order.getClient().getName(), DEFAULT_CONTRACT, null,
                merge(lines));
    }

    private Document purchaseFromCrm(Purchase purchase) {
        List<Line> lines = new ArrayList<>();
        for (PurchaseItem item : purchase.getItems()) {
            BigDecimal sum = item.getTotalPrice() != null ? item.getTotalPrice() : BigDecimal.ZERO;
            BigDecimal price = item.getQuantity().signum() == 0 ? BigDecimal.ZERO
                    : sum.divide(item.getQuantity(), 2, RoundingMode.HALF_UP);
            lines.add(new Line("", item.getIngredient().getName(), item.getQuantity(),
                    unitOrDefault(item.getIngredient().getUnit()), price, sum));
        }
        return new Document(purchaseInvoiceNumber(purchase), purchase.getPurchaseDate(),
                purchase.getCounterparty().getName(), supplierName(), DEFAULT_CONTRACT, null, merge(lines));
    }

    /** Номер приходной накладной — номер "Поступления" в 1С; до выгрузки в 1С — номер закупки. */
    public static String purchaseInvoiceNumber(Purchase purchase) {
        return purchase.getOneCDocumentNumber() != null
                ? printNumber(purchase.getOneCDocumentNumber()) : String.valueOf(purchase.getId());
    }

    private Document returnFromCrm(ProductReturn productReturn) {
        List<Line> lines = new ArrayList<>();
        for (ProductReturnItem item : productReturn.getItems()) {
            lines.add(new Line("", item.getProduct().getName(), item.getQuantity(),
                    unitOrDefault(item.getProduct().getUnit()), item.getPrice(),
                    item.getPrice().multiply(item.getQuantity())));
        }
        String number = productReturn.getOneCDocumentNumber() != null
                ? printNumber(productReturn.getOneCDocumentNumber()) : String.valueOf(productReturn.getId());
        // в возврате стороны меняются местами: поставщик — клиент, покупатель — мы (как печатает 1С)
        return new Document(number, productReturn.getReturnDate(), productReturn.getClient().getName(),
                supplierName(), DEFAULT_CONTRACT, null, merge(lines));
    }

    /**
     * Повторяющийся товар (тот же код/наименование и та же цена) — одной строкой с суммарным
     * количеством, в порядке первого появления. Разные цены на один товар остаются отдельными строками.
     */
    static List<Line> merge(List<Line> lines) {
        Map<String, Line> merged = new LinkedHashMap<>();
        for (Line line : lines) {
            String key = line.code() + "|" + line.name() + "|" + line.unit() + "|" + line.price().stripTrailingZeros().toPlainString();
            merged.merge(key, line, (a, b) -> new Line(a.code(), a.name(), a.quantity().add(b.quantity()), a.unit(),
                    a.price(), a.sum().add(b.sum())));
        }
        return new ArrayList<>(merged.values());
    }

    /**
     * Зав. складом — ФИО активного пользователя CRM с ролью "Кладовщик (склад ГП)" (самого раннего,
     * если их несколько): переименовали в CRM — меняется и в накладной. Нет такого — из настройки.
     */
    private String warehouseManager() {
        return userRepository.findFirstByRoleAndEnabledTrueOrderByIdAsc(Role.WAREHOUSE_FG)
                .map(User::getFullName)
                .filter(name -> !name.isBlank())
                .map(String::trim)
                .orElse(defaultWarehouseManager);
    }

    private String supplierName() {
        String name = companyProfileService.get().getName();
        return name == null || name.isBlank() ? DEFAULT_SUPPLIER : name;
    }

    private static String unitOrDefault(String unit) {
        return unit == null || unit.isBlank() ? "шт" : unit;
    }

    /** 1С печатает номер без ведущих нулей: "00000000161" → "161". */
    static String printNumber(String number) {
        String trimmed = number.trim();
        return trimmed.matches("\\d+") ? String.valueOf(Long.parseLong(trimmed)) : trimmed;
    }

    /**
     * Реализация по заказу и закреплённые за ним возвраты. Подписи: в продаже "Отпустил" —
     * warehouseManager, "Получил" — clientName (имя клиента в CRM); в возврате наоборот.
     */
    public record Invoice(Document sale, List<Document> returns, String clientName, String warehouseManager) {

        /** К оплате: сумма продажи минус все возвраты. */
        public BigDecimal payable() {
            return returns.stream().map(Document::total).reduce(sale.total(), BigDecimal::subtract);
        }
    }

    /**
     * Один документ накладной. В продаже supplier — мы, buyer — клиент; в возврате наоборот,
     * как в шапке печатной формы 1С. warehouse == null — строка "Склад" не печатается.
     */
    public record Document(String number, LocalDate date, String supplier, String buyer, String contract,
                           String warehouse, List<Line> lines) {

        public BigDecimal total() {
            return lines.stream().map(Line::sum).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    public record Line(String code, String name, BigDecimal quantity, String unit, BigDecimal price, BigDecimal sum) {
    }
}
