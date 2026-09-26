package com.confectionery.crm.service;

import com.confectionery.crm.domain.Order;
import com.confectionery.crm.domain.OrderItem;
import com.confectionery.crm.domain.ProductReturn;
import com.confectionery.crm.domain.ProductReturnItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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
    private static final String DEFAULT_SUPPLIER = "Юг Сладости";
    private static final String DEFAULT_CONTRACT = "Без договора";

    private final OneCClient oneCClient;
    private final CompanyProfileService companyProfileService;

    public Invoice load(Order order) {
        Document sale = fromOneC(SALE, order.getOneCDocumentKey(), "заказу №" + order.getId(), false);
        if (sale == null) {
            sale = saleFromCrm(order);
        }
        List<Document> returns = new ArrayList<>();
        order.getReturns().stream()
                .sorted(Comparator.comparing(ProductReturn::getReturnDate))
                .forEach(r -> {
                    Document doc = fromOneC(RETURN, r.getOneCDocumentKey(), "возврату №" + r.getId(), true);
                    returns.add(doc != null ? doc : returnFromCrm(r));
                });
        return new Invoice(sale, returns);
    }

    private Document fromOneC(String entitySet, String ref, String what, boolean isReturn) {
        if (!oneCClient.isConfigured() || ref == null) {
            return null;
        }
        try {
            OneCClient.PrintableDocument doc = oneCClient.fetchPrintableDocument(entitySet, ref);
            List<Line> lines = doc.lines().stream()
                    .map(l -> new Line(l.code(), l.name(), l.quantity(), unitOrDefault(l.unit()), l.price(), l.sum()))
                    .toList();
            String contract = doc.contract().isBlank() ? DEFAULT_CONTRACT : doc.contract();
            // печатная форма возврата в 1С: поставщик — клиент, покупатель — мы, склада в шапке нет
            return isReturn
                    ? new Document(printNumber(doc.number()), doc.date(), doc.contragent(), doc.organization(),
                            contract, null, lines)
                    : new Document(printNumber(doc.number()), doc.date(), doc.organization(), doc.contragent(),
                            contract, doc.warehouse(), lines);
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
        return new Document(number, date, supplierName(), order.getClient().getName(), DEFAULT_CONTRACT, null, lines);
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
                supplierName(), DEFAULT_CONTRACT, null, lines);
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

    /** Реализация по заказу и закреплённые за ним возвраты. */
    public record Invoice(Document sale, List<Document> returns) {

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
