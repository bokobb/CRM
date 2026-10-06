package com.confectionery.crm.service;

import com.confectionery.crm.domain.Product;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Упаковки товара для «Объём и масса»: до двух вариантов коробки (Product.unitsPerBox/
 * boxVolumeM3 и unitsPerBox2/boxVolume2M3). Штуки в коробке, если не заполнены, берутся из
 * названия, как их там пишут: «Барни (15 шт)» → 15, «Коржики (12/24 шт)» → 12 и 24 (какой
 * вариант — решает клиент, выбирается в приложении при отгрузке).
 */
public final class ProductPacking {

    private static final Pattern NAME_UNITS = Pattern.compile("\\((\\d+)(?:\\s*/\\s*(\\d+))?\\s*шт\\.?\\)");

    public record Option(BigDecimal unitsPerBox, BigDecimal boxVolumeM3) {
    }

    private ProductPacking() {
    }

    /** Штуки в коробке из названия: пусто, одно или два числа. */
    public static List<BigDecimal> unitsFromName(String name) {
        List<BigDecimal> result = new ArrayList<>();
        if (name == null) return result;
        Matcher m = NAME_UNITS.matcher(name);
        if (m.find()) {
            result.add(new BigDecimal(m.group(1)));
            if (m.group(2) != null) result.add(new BigDecimal(m.group(2)));
        }
        return result;
    }

    public static List<Option> options(Product p) {
        List<BigDecimal> fromName = unitsFromName(p.getName());
        List<Option> result = new ArrayList<>();
        BigDecimal first = positive(p.getUnitsPerBox()) != null ? p.getUnitsPerBox()
                : fromName.isEmpty() ? null : fromName.get(0);
        if (first != null) result.add(new Option(first, p.getBoxVolumeM3()));
        BigDecimal second = positive(p.getUnitsPerBox2()) != null ? p.getUnitsPerBox2()
                : fromName.size() > 1 ? fromName.get(1) : null;
        if (second != null) result.add(new Option(second, p.getBoxVolume2M3()));
        return result;
    }

    /** Вариант с запрошенным количеством в коробке, иначе первый; null — упаковка не известна. */
    public static Option choose(Product p, BigDecimal requestedUnits) {
        List<Option> options = options(p);
        if (options.isEmpty()) return null;
        if (requestedUnits != null) {
            for (Option o : options) {
                if (o.unitsPerBox().compareTo(requestedUnits) == 0) return o;
            }
        }
        return options.get(0);
    }

    private static BigDecimal positive(BigDecimal v) {
        return v == null || v.signum() <= 0 ? null : v;
    }
}
