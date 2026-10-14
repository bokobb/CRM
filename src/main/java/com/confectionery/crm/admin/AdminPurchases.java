package com.confectionery.crm.admin;

import com.confectionery.crm.domain.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * Покупки по макету (часть 2): статус в 1С («Поступление товаров»: номер, в очереди, не ушла), строка списка,
 * цена за единицу против прошлой покупки, что обычно привозит поставщик (по его покупкам).
 */
public final class AdminPurchases {

    private AdminPurchases() {
    }

    static final AdminFormat F = new AdminFormat();

    /** ok — проведена (есть номер), queue — ждёт отправки, err — не ушла. */
    public record OneC(String key, String label, String big, String cls) {
    }

    public static OneC oneC(Purchase p) {
        if (p.getOneCSyncError() != null) return new OneC("err", "ошибка", "1С: не ушла", "tag--bad");
        if (p.getOneCDocumentNumber() != null) {
            String no = p.getOneCDocumentNumber();
            return new OneC("ok", no, "1С: № " + no, "tag--ok");
        }
        return new OneC("queue", "в очереди", "1С: в очереди", "");
    }

    public static BigDecimal sum(Purchase p) {
        return p.getTotalSum();
    }

    public static String unit(Ingredient i) {
        return AdminRequests.unit(i);
    }

    public static BigDecimal unitPrice(PurchaseItem it) {
        if (it.getTotalPrice() == null || it.getQuantity() == null || it.getQuantity().signum() <= 0) return null;
        return it.getTotalPrice().divide(it.getQuantity(), 2, RoundingMode.HALF_UP);
    }

    // ------------------------------------------------------------------ строка списка

    public record Row(Purchase p, OneC oneC, String names, String qtys, BigDecimal sum) {
        public boolean photo() {
            return p.getImageUrl() != null && !p.getImageUrl().isBlank();
        }
    }

    public static Row row(Purchase p) {
        List<String> names = p.getItems().stream().map(i -> i.getIngredient().getName()).toList();
        String n = String.join(", ", names.subList(0, Math.min(3, names.size()))) + (names.size() > 3 ? " и ещё " + (names.size() - 3) : "");
        String q = String.join(" · ", p.getItems().stream().limit(3).map(i -> AdminRequests.qu(i.getQuantity(), unit(i.getIngredient()))).toList());
        return new Row(p, oneC(p), n, q, sum(p));
    }

    // ------------------------------------------------------------------ карточка: цена против прошлой покупки

    public record Line(PurchaseItem it, BigDecimal price, String diff) {
        public String unit() {
            return AdminPurchases.unit(it.getIngredient());
        }
    }

    static boolean before(Purchase a, Purchase b) {
        int c = a.getPurchaseDate().compareTo(b.getPurchaseDate());
        if (c != 0) return c < 0;
        if (a.getCreatedAt() != null && b.getCreatedAt() != null) return a.getCreatedAt().isBefore(b.getCreatedAt());
        return a.getId() < b.getId();
    }

    /** {@code pricedNewestFirst} — строки покупок этого сырья, новые первыми (PurchaseRepository.findPricedItemsNewestFirst). */
    public static List<Line> lines(Purchase p, List<PurchaseItem> pricedNewestFirst) {
        List<Line> out = new ArrayList<>();
        for (PurchaseItem it : p.getItems()) {
            BigDecimal price = unitPrice(it);
            PurchaseItem prev = null;
            for (PurchaseItem x : pricedNewestFirst) {
                if (!x.getIngredient().getId().equals(it.getIngredient().getId()) || x.getPurchase() == null) continue;
                if (x.getPurchase().getId().equals(p.getId()) || !before(x.getPurchase(), p)) continue;
                prev = x;
                break;
            }
            String diff;
            BigDecimal pp = prev == null ? null : unitPrice(prev);
            if (price == null) diff = "цена не указана";
            else if (pp == null || pp.signum() == 0) diff = "первая покупка";
            else {
                double d = (price.doubleValue() - pp.doubleValue()) / pp.doubleValue() * 100;
                diff = Math.abs(d) < 0.5 ? "как в прошлый раз" : (d > 0 ? "+" : "−") + F.dec(Math.abs(d), 1) + AdminFormat.NB + "% к № " + prev.getPurchase().getId();
            }
            out.add(new Line(it, price, diff));
        }
        return out;
    }

    // ------------------------------------------------------------------ поставщики

    /** Что обычно привозит: три сырья с наибольшей суммой покупок. */
    public static String what(Collection<Purchase> ofSupplier) {
        Map<String, BigDecimal> by = new HashMap<>();
        for (Purchase p : ofSupplier) for (PurchaseItem it : p.getItems()) {
            by.merge(it.getIngredient().getName(), it.getTotalPrice() == null ? BigDecimal.ONE : it.getTotalPrice(), BigDecimal::add);
        }
        return String.join(", ", by.entrySet().stream().sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                .limit(3).map(e -> e.getKey().toLowerCase(Locale.ROOT).replaceAll("\\s*\\([^)]*\\)", "")).toList());
    }

    public record Supplier(Counterparty c, String what, int materials, Purchase last, BigDecimal sum30, boolean linked, boolean hasPurchases) {
    }

    public static List<Supplier> suppliers(List<Counterparty> all, List<Purchase> purchases) {
        LocalDate from = LocalDate.now().minusDays(29);
        Map<Long, List<Purchase>> by = new HashMap<>();
        for (Purchase p : purchases) if (p.getCounterparty() != null) by.computeIfAbsent(p.getCounterparty().getId(), k -> new ArrayList<>()).add(p);
        List<Supplier> out = new ArrayList<>();
        for (Counterparty c : all) {
            List<Purchase> ps = by.getOrDefault(c.getId(), List.of());
            Purchase last = ps.stream().max((a, b) -> before(a, b) ? -1 : before(b, a) ? 1 : 0).orElse(null);
            BigDecimal s30 = ps.stream().filter(p -> !p.getPurchaseDate().isBefore(from)).map(Purchase::getTotalSum).reduce(BigDecimal.ZERO, BigDecimal::add);
            Set<Long> mats = new HashSet<>();
            ps.forEach(p -> p.getItems().forEach(i -> mats.add(i.getIngredient().getId())));
            out.add(new Supplier(c, what(ps), mats.size(), last, s30, c.getOneCContragentKey() != null, !ps.isEmpty()));
        }
        return out;
    }

    // ------------------------------------------------------------------ форма (JSON в странице)

    public static Map<String, Object> formData(Purchase p, List<Counterparty> sups, List<Ingredient> active, Map<Long, AdminMaterials.Price> prices,
                                               Map<Long, Long> lastSupplier, Map<String, Object> preset) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("today", LocalDate.now().toString());
        m.put("no", p == null ? null : p.getId());
        m.put("sup", p == null ? (preset == null ? null : preset.get("sup")) : p.getCounterparty().getId());
        m.put("date", p == null ? LocalDate.now().toString() : p.getPurchaseDate().toString());
        m.put("sups", sups.stream().map(c -> Map.of("id", c.getId(), "name", c.getName(), "oneC", c.getOneCContragentKey() != null)).toList());
        m.put("ings", active.stream().filter(i -> !AdminRequests.untracked(i)).sorted(Comparator.comparing(Ingredient::getName, String.CASE_INSENSITIVE_ORDER)).map(i -> {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", i.getId());
            x.put("name", i.getName());
            x.put("unit", unit(i));
            AdminMaterials.Price pr = prices.get(i.getId());
            x.put("price", pr == null ? null : pr.unit());
            x.put("sup", lastSupplier.get(i.getId()));
            return x;
        }).toList());
        List<Map<String, Object>> rows = new ArrayList<>();
        if (p != null) {
            for (PurchaseItem it : p.getItems()) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("m", it.getIngredient().getId());
                r.put("q", it.getQuantity());
                r.put("s", it.getTotalPrice());
                rows.add(r);
            }
        } else if (preset != null && preset.get("m") != null) {
            rows.add(preset);
        }
        m.put("rows", rows);
        return m;
    }
}
