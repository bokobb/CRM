package com.confectionery.crm.admin;

import com.confectionery.crm.domain.*;
import com.confectionery.crm.service.NumberToWordsRu;
import com.confectionery.crm.service.OneCInvoiceService;
import com.confectionery.crm.service.OrderService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Накладная по заказу в карточке заказа (часть 2 макета) — данные для окна (inv.js), по тем же правилам, что
 * PDF и приложение:
 * <ul>
 * <li>«По сумме» — как InvoicePdfService по данным CRM (OneCInvoiceService.saleFromCrm/returnFromCrm):
 * номер — «Реализации» 1С, до выгрузки — заказа; возвраты по дате, в возврате поставщик — клиент, покупатель — мы;
 * подписи «Отпустил/Получил» — кладовщик и клиент (в возврате наоборот); итог «к оплате» = продажа − возвраты;
 * суммы прописью — NumberToWordsRu. Сам PDF строит InvoicePdfService — с документами 1С, если они есть.</li>
 * <li>«Объём и масса» — как packing_pdf.dart: коробок = ⌈штук / в коробке⌉, объём = коробок × объём коробки,
 * масса = штук × вес единицы (весовой товар без веса — масса = количество), дата — отгрузки, до неё — заказа.</li>
 * </ul>
 */
public final class AdminInvoice {

    private AdminInvoice() {
    }

    static final Pattern UNITS = Pattern.compile("\\((\\d+)(?:\\s*/\\s*(\\d+))?\\s*шт\\.?\\)");
    static final String DEFAULT_SUPPLIER = "Юг Сладости";

    /** Варианты «штук в коробке» (0, 1 или 2) — как Product.packingUnits в приложении. */
    public static List<BigDecimal> packingUnits(Product p) {
        List<BigDecimal> fromName = new ArrayList<>();
        Matcher m = UNITS.matcher(p.getName() == null ? "" : p.getName());
        if (m.find()) {
            fromName.add(new BigDecimal(m.group(1)));
            if (m.group(2) != null) fromName.add(new BigDecimal(m.group(2)));
        }
        BigDecimal first = p.getUnitsPerBox() != null && p.getUnitsPerBox().signum() > 0 ? p.getUnitsPerBox() : fromName.isEmpty() ? null : fromName.get(0);
        BigDecimal second = p.getUnitsPerBox2() != null && p.getUnitsPerBox2().signum() > 0 ? p.getUnitsPerBox2() : fromName.size() > 1 ? fromName.get(1) : null;
        List<BigDecimal> out = new ArrayList<>();
        if (first != null) out.add(first);
        if (second != null) out.add(second);
        return out;
    }

    static Map<String, Object> line(Product p, BigDecimal q, BigDecimal price) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("code", p.getOneCCode() == null ? "" : p.getOneCCode());
        m.put("name", p.getName());
        m.put("unit", p.getUnit() == null || p.getUnit().isBlank() ? "шт" : p.getUnit());
        m.put("q", q);
        m.put("price", price);
        m.put("units", packingUnits(p));
        m.put("vol", p.getBoxVolumeM3());
        m.put("vol2", p.getBoxVolume2M3());
        m.put("kg", p.getUnitWeightKg());
        return m;
    }

    static BigDecimal sum(List<BigDecimal[]> lines) {
        return lines.stream().map(x -> x[0].multiply(x[1])).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * @param supplier  название из профиля компании (пусто — «Юг Сладости», как в PDF и приложении)
     * @param warehouse склад в шапке — как назван в 1С (crm.invoice.warehouse-name)
     * @param manager   кладовщик в подписи — OneCInvoiceService.warehouseManager()
     */
    public static Map<String, Object> data(Order o, String supplier, String warehouse, String manager) {
        Map<String, Object> m = new LinkedHashMap<>();
        String buyer = o.getClient().getName() == null ? "" : o.getClient().getName().trim();
        m.put("id", o.getId());
        m.put("no", OrderService.invoiceNumber(o));
        m.put("date", (o.getShippedAt() != null ? o.getShippedAt().toLocalDate() : LocalDate.now()).toString());
        m.put("vdate", (o.getShippedAt() != null ? o.getShippedAt() : o.getCreatedAt()).toLocalDate().toString());
        m.put("client", o.getClient().getId());
        m.put("buyer", buyer);
        m.put("supplier", supplier == null || supplier.isBlank() ? DEFAULT_SUPPLIER : supplier.trim());
        m.put("warehouse", warehouse == null || warehouse.isBlank() ? null : warehouse.trim());
        m.put("inOneC", o.getOneCDocumentKey() != null);
        m.put("manager", manager == null ? "" : manager.trim());
        m.put("lines", o.getItems().stream().map(i -> line(i.getProduct(), i.getQuantity(), i.getPrice())).toList());
        BigDecimal sale = sum(o.getItems().stream().map(i -> new BigDecimal[]{i.getPrice(), i.getQuantity()}).toList());
        m.put("words", NumberToWordsRu.amountInWords(sale));
        List<Map<String, Object>> rets = new ArrayList<>();
        BigDecimal pay = sale;
        for (ProductReturn r : o.getReturns().stream().sorted(Comparator.comparing(ProductReturn::getReturnDate)).toList()) {
            BigDecimal total = sum(r.getItems().stream().map(i -> new BigDecimal[]{i.getPrice(), i.getQuantity()}).toList());
            pay = pay.subtract(total);
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("no", r.getOneCDocumentNumber() != null ? OneCInvoiceService.printNumber(r.getOneCDocumentNumber()) : String.valueOf(r.getId()));
            x.put("date", r.getReturnDate().toString());
            x.put("lines", r.getItems().stream().map(i -> line(i.getProduct(), i.getQuantity(), i.getPrice())).toList());
            x.put("words", NumberToWordsRu.amountInWords(total));
            rets.add(x);
        }
        m.put("returns", rets);
        m.put("payWords", NumberToWordsRu.amountInWords(pay));
        return m;
    }
}
