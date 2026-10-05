package com.confectionery.crm.api;

import com.confectionery.crm.api.dto.DashboardInsightsDto;
import com.confectionery.crm.api.dto.DayFeedDto;
import com.confectionery.crm.api.dto.RawInsightsDto;
import com.confectionery.crm.domain.ProductionRequestLine;
import com.confectionery.crm.domain.ProductionRequestStatus;
import com.confectionery.crm.domain.Ingredient;
import com.confectionery.crm.domain.Order;
import com.confectionery.crm.domain.OrderStatus;
import com.confectionery.crm.domain.ProductReturn;
import com.confectionery.crm.domain.ProductionRequest;
import com.confectionery.crm.repository.OrderRepository;
import com.confectionery.crm.repository.ProductReturnRepository;
import com.confectionery.crm.domain.Role;
import com.confectionery.crm.service.IngredientService;
import com.confectionery.crm.service.UserService;
import org.springframework.security.core.Authentication;
import com.confectionery.crm.service.ProductionRequestService;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Данные для альтернативных компоновок дашборда в приложении (Настройки › Оформление):
 * «График + Требует внимания» и «Лента дня».
 */
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardExtrasApiController {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM");

    private final OrderRepository orderRepository;
    private final ProductReturnRepository productReturnRepository;
    private final IngredientService ingredientService;
    private final ProductionRequestService productionRequestService;
    private final UserService userService;

    /** Продажа за день = отгружено минус возвраты, как на обычном дашборде. */
    private BigDecimal netSales(LocalDate from, LocalDate to) {
        LocalDateTime start = from.atStartOfDay();
        LocalDateTime end = to.plusDays(1).atStartOfDay();
        return orderRepository.sumSalesBetween(start, end).subtract(productReturnRepository.sumForSalesBetween(start, end));
    }

    @GetMapping("/insights")
    @Transactional(readOnly = true)
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT','SALES')")
    public DashboardInsightsDto insights(@RequestParam(required = false) LocalDate to) {
        LocalDate end = to != null ? to : LocalDate.now();
        LocalDate start = end.minusDays(6);
        List<DashboardInsightsDto.DaySales> days = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            days.add(new DashboardInsightsDto.DaySales(d, netSales(d, d)));
        }
        BigDecimal week = days.stream().map(DashboardInsightsDto.DaySales::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal prevWeek = netSales(start.minusDays(7), start.minusDays(1));

        List<DashboardInsightsDto.Alert> alerts = new ArrayList<>();
        for (Ingredient i : ingredientService.findLowStock()) {
            alerts.add(new DashboardInsightsDto.Alert("LOW_STOCK",
                    i.getName() + " — " + qty(i.getQuantityInStock()) + " из " + qty(i.getMinThreshold()) + " " + i.getUnit(),
                    "ниже нормы, нужна закупка", null));
        }
        for (Order o : orderRepository.findOverdueDebts(LocalDate.now())) {
            alerts.add(new DashboardInsightsDto.Alert("OVERDUE_DEBT",
                    "Долг: " + o.getClient().getName(),
                    money(o.getNetTotal()) + " · срок " + o.getDebtDueDate().format(DAY), o.getId()));
        }
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        for (Order o : orderRepository.findByStatusInAndCreatedAtBefore(List.of(OrderStatus.NEW, OrderStatus.READY), todayStart)) {
            long dayNo = ChronoUnit.DAYS.between(o.getCreatedAt().toLocalDate(), LocalDate.now()) + 1;
            String what = o.getStatus() == OrderStatus.NEW ? "собирается" : "ждёт отгрузки";
            alerts.add(new DashboardInsightsDto.Alert("STUCK_ORDER",
                    "Заказ №" + o.getId() + " " + what + " " + dayNo + "-й день",
                    o.getClient().getName() + " · " + money(o.getTotalPrice()), o.getId()));
        }
        return new DashboardInsightsDto(days, week, prevWeek, alerts);
    }

    /** Замесы заявок, собранных складом сырья в [from, to]. */
    private static BigDecimal batches(List<ProductionRequest> all, LocalDate from, LocalDate to) {
        return all.stream()
                .filter(r -> r.getCollectedAt() != null && r.getStatus() != ProductionRequestStatus.CANCELLED)
                .filter(r -> !r.getCollectedAt().toLocalDate().isBefore(from) && !r.getCollectedAt().toLocalDate().isAfter(to))
                .flatMap(r -> r.getLines().stream())
                .map(ProductionRequestLine::getBatches)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @GetMapping("/raw-insights")
    @Transactional(readOnly = true)
    public RawInsightsDto rawInsights(@RequestParam(required = false) LocalDate to) {
        LocalDate end = to != null ? to : LocalDate.now();
        LocalDate start = end.minusDays(6);
        List<ProductionRequest> all = productionRequestService.findAll();
        List<RawInsightsDto.Day> days = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            final LocalDate day = d;
            long count = all.stream()
                    .filter(r -> r.getCollectedAt() != null && r.getStatus() != ProductionRequestStatus.CANCELLED
                            && r.getCollectedAt().toLocalDate().isEqual(day))
                    .count();
            days.add(new RawInsightsDto.Day(d, batches(all, d, d), count));
        }
        BigDecimal week = batches(all, start, end);
        BigDecimal prevWeek = batches(all, start.minusDays(7), start.minusDays(1));

        List<RawInsightsDto.Alert> alerts = new ArrayList<>();
        for (Ingredient i : ingredientService.findLowStock()) {
            boolean minus = i.getQuantityInStock() != null && i.getQuantityInStock().signum() < 0;
            alerts.add(new RawInsightsDto.Alert("LOW_STOCK",
                    i.getName() + " — " + qty(i.getQuantityInStock()) + " из " + qty(i.getMinThreshold()) + " " + i.getUnit(),
                    minus ? "ушло в минус" : "ниже нормы, нужна закупка", null));
        }
        LocalDate today = LocalDate.now();
        long pending = 0;
        long awaiting = 0;
        for (ProductionRequest r : all) {
            if (r.getStatus() == ProductionRequestStatus.NEW) {
                pending++;
                if (r.getCreatedAt().toLocalDate().isBefore(today)) {
                    long dayNo = ChronoUnit.DAYS.between(r.getCreatedAt().toLocalDate(), today) + 1;
                    alerts.add(new RawInsightsDto.Alert("STUCK_REQUEST",
                            "Заявка №" + r.getId() + " не собрана " + dayNo + "-й день", title(r), r.getId()));
                }
            } else if (r.getStatus() == ProductionRequestStatus.COLLECTED) {
                awaiting++;
                if (r.getCollectedAt() != null && r.getCollectedAt().toLocalDate().isBefore(today)) {
                    alerts.add(new RawInsightsDto.Alert("AWAITING_RECEIPT",
                            "Заявка №" + r.getId() + " ждёт приёма на склад ГП",
                            "собрана " + r.getCollectedAt().format(DAY), r.getId()));
                }
            }
        }
        return new RawInsightsDto(days, week, prevWeek, pending, awaiting, alerts);
    }

    @GetMapping("/feed")
    @Transactional(readOnly = true)
    public DayFeedDto feed(@RequestParam(required = false) LocalDate date, Authentication authentication) {
        // Склады (ГП, сырьё) видят события дня, но без денег — суммы продаж им не показываем.
        Role role = userService.roleOf(authentication.getName());
        boolean money = role == Role.ADMIN || role == Role.ACCOUNTANT || role == Role.SALES;
        LocalDate day = date != null ? date : LocalDate.now();
        LocalDateTime start = day.atStartOfDay();
        LocalDateTime end = day.plusDays(1).atStartOfDay();
        List<DayFeedDto.Event> events = new ArrayList<>();

        List<Order> created = orderRepository.findByCreatedAtBetween(start, end);
        for (Order o : created) {
            String meta = join(money ? money(o.getTotalPrice()) : null,
                    Boolean.TRUE.equals(o.getFromWhatsapp()) ? "из WhatsApp" : null,
                    o.isImportedFromOneC() ? "из 1С" : null);
            events.add(new DayFeedDto.Event(o.getCreatedAt(), "ORDER_NEW",
                    "Новый заказ №" + o.getId() + " · " + o.getClient().getName(), meta, o.getId(), null));
        }
        for (Order o : orderRepository.findByShippedAtBetween(start, end)) {
            if (o.getStatus() == OrderStatus.CANCELLED) continue;
            String meta = join(money ? money(o.getTotalPrice()) : null, o.getVehicleNumber());
            events.add(new DayFeedDto.Event(o.getShippedAt(), "ORDER_SHIPPED",
                    "Отгружен №" + o.getId() + " · " + o.getClient().getName(), meta, o.getId(), null));
        }
        for (ProductReturn r : productReturnRepository.findByReturnDateBetweenOrderByCreatedAtDesc(day, day)) {
            events.add(new DayFeedDto.Event(r.getCreatedAt(), "RETURN",
                    "Возврат · " + r.getClient().getName(),
                    join(money ? "−" + money(r.getTotalSum()) : null, r.getReason()),
                    r.getOrder() != null ? r.getOrder().getId() : null, null));
        }
        for (ProductionRequest pr : productionRequestService.findAll()) {
            if (!pr.getCreatedAt().isBefore(start) && pr.getCreatedAt().isBefore(end)) {
                events.add(new DayFeedDto.Event(pr.getCreatedAt(), "REQUEST_NEW",
                        "Заявка на сырьё №" + pr.getId() + " создана", title(pr), null, pr.getId()));
            }
            if (pr.getCollectedAt() != null && !pr.getCollectedAt().isBefore(start) && pr.getCollectedAt().isBefore(end)) {
                events.add(new DayFeedDto.Event(pr.getCollectedAt(), "REQUEST_COLLECTED",
                        "Цех: заявка №" + pr.getId() + " собрана", "ждёт приёма на склад ГП", null, pr.getId()));
            }
        }
        events.sort(Comparator.comparing(DayFeedDto.Event::time).reversed());
        return new DayFeedDto(day, money ? netSales(day, day) : null, created.size(), events);
    }

    /** Непустые части через « · ». */
    private static String join(String... parts) {
        return java.util.Arrays.stream(parts).filter(p -> p != null && !p.isBlank())
                .collect(java.util.stream.Collectors.joining(" · "));
    }

    private static String title(ProductionRequest pr) {
        return pr.getTitle() != null && !pr.getTitle().isBlank() ? pr.getTitle() : "";
    }

    private static String qty(BigDecimal v) {
        return v == null ? "0" : v.stripTrailingZeros().toPlainString();
    }

    private static String money(BigDecimal v) {
        if (v == null) return "0 ₸";
        String s = String.format("%,d", v.setScale(0, RoundingMode.HALF_UP).longValue()).replace(',', ' ');
        return s + " ₸";
    }
}
