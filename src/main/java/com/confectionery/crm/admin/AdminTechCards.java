package com.confectionery.crm.admin;

import com.confectionery.crm.api.dto.TechCardDto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Техкарта как в макете (techCalc, tcWarnings), на тех данных, что есть в CRM: состав из рецептуры с долями,
 * цены по последней закупке (старше 45 дней — «старые»), сырьё на замес, на штуку и на килограмм, доля сырья
 * в цене, тесто на штуку, масса нетто и упёк, состав для этикетки по убыванию массы, замечания. Чего в CRM нет
 * (отход сырья, аллергены, пищевая ценность, упаковка с ценами, накладные, версии) — не считаем.
 */
public final class AdminTechCards {

    private AdminTechCards() {
    }

    static final int STALE_DAYS = 45;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000);

    /** Строка состава: количество в единицах сырья, в килограммах (если это масса или объём), доля, цена, стоимость, остаток. */
    public record Row(Long ingredientId, String name, String unit, BigDecimal qty, BigDecimal kg, BigDecimal share,
                      BigDecimal unitPrice, LocalDate priceDate, boolean stale, BigDecimal cost, BigDecimal stock) {
    }

    public record Calc(List<Row> rows, BigDecimal batchKg, boolean allMass, BigDecimal batchCost, boolean costFull,
                       BigDecimal yield, BigDecimal perUnit, BigDecimal perKg, BigDecimal dough, BigDecimal finished,
                       BigDecimal loss, BigDecimal perBox, BigDecimal salePrice, BigDecimal rawShare, List<String> label,
                       List<String> warns, int ccp) {
        public boolean hasRows() {
            return !rows.isEmpty();
        }

        /** Упёк вне разумного: тесто легче готового или потеря больше 40 %. */
        public boolean lossBad() {
            return loss != null && (loss.signum() < 0 || loss.compareTo(BigDecimal.valueOf(40)) > 0);
        }

        /** Знаков после запятой у сырья на штуку: до 100 ₸ — один (12,4 ₸), дальше — целые. */
        public int perUnitScale() {
            return perUnit != null && perUnit.compareTo(HUNDRED) < 0 ? 1 : 0;
        }

        /** Состав для этикетки одной строкой. */
        public String labelText() {
            return String.join(", ", label);
        }
    }

    /** Количество в килограммах: кг и л — как есть (вода, молоко ≈ 1 кг/л), г и мл — ÷ 1000; штуки и прочее — null. */
    static BigDecimal kg(BigDecimal q, String unit) {
        if (q == null) return null;
        String u = unit == null ? "кг" : unit.trim().toLowerCase(Locale.ROOT).replace(".", "");
        return switch (u) {
            case "кг", "л" -> q;
            case "г", "мл" -> q.divide(THOUSAND, 6, RoundingMode.HALF_UP);
            default -> null;
        };
    }

    /**
     * @param stock  остатки сырья на складе по id
     * @param perBox упаковок (единиц товара) в коробке, null — не задано
     * @param semi   полуфабрикат: на продажу не фасуется, этикетки и цены в каталоге нет
     * @param prices показывать ли замечания о ценах (цены видят администратор и бухгалтер)
     */
    public static Calc calc(TechCardDto t, Map<Long, BigDecimal> stock, BigDecimal perBox, boolean semi, boolean prices, LocalDate today) {
        List<Row> raw = new ArrayList<>();
        BigDecimal kgSum = BigDecimal.ZERO, cost = BigDecimal.ZERO;
        boolean allMass = true, costFull = true;
        List<String> stale = new ArrayList<>(), noPrice = new ArrayList<>();
        for (TechCardDto.Item i : t.items()) {
            BigDecimal kg = kg(i.quantity(), i.unit());
            if (kg == null) allMass = false;
            else kgSum = kgSum.add(kg);
            BigDecimal c = i.unitPrice() == null || i.quantity() == null ? null : i.unitPrice().multiply(i.quantity());
            if (c == null) {
                costFull = false;
                noPrice.add(i.name());
            } else {
                cost = cost.add(c);
            }
            boolean old = i.priceDate() != null && ChronoUnit.DAYS.between(i.priceDate(), today) > STALE_DAYS;
            if (old) stale.add(i.name());
            raw.add(new Row(i.ingredientId(), i.name(), i.unit() == null ? "кг" : i.unit(), i.quantity(), kg, null,
                    i.unitPrice(), i.priceDate(), old, c, stock.get(i.ingredientId())));
        }
        // доли — от массы закладки; по убыванию массы, сырьё в штуках — в конце, как в рецептуре
        BigDecimal total = kgSum;
        List<Row> rows = new ArrayList<>();
        for (Row r : raw) {
            BigDecimal share = r.kg() == null || total.signum() == 0 ? null : r.kg().multiply(HUNDRED).divide(total, 1, RoundingMode.HALF_UP);
            rows.add(new Row(r.ingredientId(), r.name(), r.unit(), r.qty(), r.kg(), share, r.unitPrice(), r.priceDate(), r.stale(), r.cost(), r.stock()));
        }
        List<Row> mass = new ArrayList<>(rows.stream().filter(r -> r.kg() != null).toList());
        mass.sort(Comparator.comparing(Row::kg).reversed());
        List<Row> sorted = new ArrayList<>(mass);
        rows.stream().filter(r -> r.kg() == null).forEach(sorted::add);

        BigDecimal yield = t.yieldQuantity() == null || t.yieldQuantity().signum() <= 0 ? null : t.yieldQuantity();
        boolean has = !sorted.isEmpty();
        BigDecimal perUnit = has && yield != null ? cost.divide(yield, 2, RoundingMode.HALF_UP) : null;
        BigDecimal perKg = has && kgSum.signum() > 0 && allMass ? cost.divide(kgSum, 0, RoundingMode.HALF_UP) : null;
        BigDecimal dough = has && allMass && yield != null && kgSum.signum() > 0 ? kgSum.multiply(THOUSAND).divide(yield, 1, RoundingMode.HALF_UP) : null;
        BigDecimal finished = t.netWeightGrams() != null && t.netWeightGrams().signum() > 0 ? t.netWeightGrams() : null;
        BigDecimal loss = dough != null && finished != null && dough.signum() > 0
                ? BigDecimal.ONE.subtract(finished.divide(dough, 6, RoundingMode.HALF_UP)).multiply(HUNDRED).setScale(1, RoundingMode.HALF_UP) : null;
        BigDecimal sale = t.salePrice() != null && t.salePrice().signum() > 0 ? t.salePrice() : null;
        BigDecimal rawShare = perUnit != null && costFull && sale != null ? perUnit.multiply(HUNDRED).divide(sale, 0, RoundingMode.HALF_UP) : null;
        List<String> label = sorted.stream().map(Row::name).toList();
        int ccp = (int) t.stages().stream().filter(TechCardDto.Stage::ccp).count();

        List<String> w = new ArrayList<>();
        if (!has) {
            w.add("Нет рецептуры — состав, себестоимость и этикетку не посчитать");
        } else {
            if (prices && !noPrice.isEmpty()) w.add("Нет цены закупки: " + String.join(", ", noPrice));
            if (prices && !stale.isEmpty()) w.add("Цены старше " + STALE_DAYS + " дней: " + String.join(", ", stale));
            if (loss != null && (loss.signum() < 0 || loss.compareTo(BigDecimal.valueOf(40)) > 0)) {
                w.add("Упёк " + loss.toPlainString().replace('.', ',') + " % — проверьте выход с замеса и массу нетто");
            }
        }
        if (!semi && finished == null) w.add("Не задана масса нетто — в «Товарах»");
        if (!semi && sale == null) w.add("Нет цены в каталоге");
        if (t.stages().isEmpty()) w.add("Не описаны этапы процесса");
        else if (ccp == 0) w.add("Нет ни одной контрольной точки (ККТ)");
        if (t.shelfLife() == null || t.shelfLife().isBlank()) w.add("Не указан срок годности");
        return new Calc(sorted, kgSum, allMass, cost, costFull, yield, perUnit, perKg, dough, finished, loss, perBox, sale, rawShare, label, w, ccp);
    }

    /** Для «Расчёта на план» в браузере: сырьё на один замес и остатки, выход, коробка. */
    public static Map<String, Object> planData(Calc c, String unit) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Row r : c.rows()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("n", r.name());
            m.put("u", r.unit());
            m.put("q", r.qty() == null ? 0 : r.qty());
            m.put("s", r.stock());
            rows.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        out.put("yield", c.yield());
        out.put("box", c.perBox());
        out.put("unit", unit == null || unit.isBlank() ? "шт" : unit);
        out.put("kg", c.allMass() ? c.batchKg() : null);
        return out;
    }
}
