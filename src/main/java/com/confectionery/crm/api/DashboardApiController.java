package com.confectionery.crm.api;

import com.confectionery.crm.api.dto.DashboardDto;
import com.confectionery.crm.api.dto.IngredientDto;
import com.confectionery.crm.api.dto.ProductionDashboardDto;
import com.confectionery.crm.api.dto.ProductionRequestSummaryDto;
import com.confectionery.crm.api.dto.OrderDto;
import com.confectionery.crm.api.dto.WarehouseDashboardDto;
import com.confectionery.crm.domain.Product;
import com.confectionery.crm.repository.ProductRepository;
import com.confectionery.crm.domain.EmployeeCostGroup;
import com.confectionery.crm.domain.OrderStatus;
import com.confectionery.crm.repository.OrderRepository;
import com.confectionery.crm.repository.ProductReturnRepository;
import com.confectionery.crm.service.IngredientService;
import com.confectionery.crm.service.NotificationService;
import com.confectionery.crm.service.ProductionRequestService;
import com.confectionery.crm.service.SalaryFundService;
import com.confectionery.crm.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardApiController {

    private final OrderRepository orderRepository;
    private final ProductReturnRepository productReturnRepository;
    private final IngredientService ingredientService;
    private final NotificationService notificationService;
    private final UserService userService;
    private final ProductionRequestService productionRequestService;
    private final SalaryFundService salaryFundService;
    private final ProductRepository productRepository;

    /** from/to не заданы — по умолчанию сегодня (в отличие от заказов/уведомлений, где "не задан" = все даты). */
    /** Продажи в деньгах — только руководству и продажам; складам свои дашборды (см. ниже). */
    @GetMapping
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT','SALES')")
    public DashboardDto get(@RequestParam(required = false) LocalDate from,
                             @RequestParam(required = false) LocalDate to,
                             Authentication authentication) {
        LocalDate rangeFrom = from != null ? from : LocalDate.now();
        LocalDate rangeTo = to != null ? to : LocalDate.now();
        LocalDateTime start = rangeFrom.atStartOfDay();
        LocalDateTime end = rangeTo.plusDays(1).atStartOfDay();

        var grouped = notificationService.grouped(8, 8, start, end, userService.roleOf(authentication.getName()));
        BigDecimal grossSales = orderRepository.sumSalesBetween(start, end);
        BigDecimal returns = productReturnRepository.sumForSalesBetween(start, end);
        DashboardDto.SalaryCosts salaryCosts = null;
        if (ProductionCostApiController.canSeeLabor(authentication)) {
            var funds = salaryFundService.periodFunds(rangeFrom, rangeTo);
            salaryCosts = new DashboardDto.SalaryCosts(funds.get(EmployeeCostGroup.SALES), funds.get(EmployeeCostGroup.ADMIN));
        }
        return new DashboardDto(
                rangeFrom, rangeTo,
                orderRepository.countByStatusAndCreatedAtBetween(OrderStatus.NEW, start, end),
                orderRepository.countByStatusAndCreatedAtBetween(OrderStatus.READY, start, end),
                grossSales.subtract(returns),
                grossSales,
                returns,
                ingredientService.findLowStock().stream().map(IngredientDto::from).toList(),
                grouped.orderGroups(),
                grouped.general(),
                salaryCosts
        );
    }

    /** Дашборд для роли WAREHOUSE_RAW — про заявки на сырьё, не про заказы клиентов. */
    @GetMapping("/production")
    public ProductionDashboardDto getProduction(@RequestParam(required = false) LocalDate from,
                                                 @RequestParam(required = false) LocalDate to) {
        LocalDate rangeFrom = from != null ? from : LocalDate.now();
        LocalDate rangeTo = to != null ? to : LocalDate.now();
        LocalDateTime start = rangeFrom.atStartOfDay();
        LocalDateTime end = rangeTo.plusDays(1).atStartOfDay();

        var summary = productionRequestService.dashboardSummary(start, end, 10);
        return new ProductionDashboardDto(
                rangeFrom, rangeTo,
                summary.counts().pending(), summary.counts().awaitingReceipt(),
                summary.counts().createdInRange(), summary.counts().collectedInRange(),
                ingredientService.findLowStock().stream().map(IngredientDto::from).toList(),
                summary.pendingRequests().stream().map(ProductionRequestSummaryDto::from).toList()
        );
    }

    /**
     * Дашборд склада ГП: что собрать и отгрузить (все текущие, независимо от периода), сколько
     * отгружено за период, сколько заявок цех собрал и ждёт приёма, и 8 товаров с наименьшим
     * остатком (порога для готовой продукции нет — показываем самые "тонкие" позиции).
     */
    @GetMapping("/warehouse")
    public WarehouseDashboardDto getWarehouse(@RequestParam(required = false) LocalDate from,
                                              @RequestParam(required = false) LocalDate to) {
        LocalDate rangeFrom = from != null ? from : LocalDate.now();
        LocalDate rangeTo = to != null ? to : LocalDate.now();
        LocalDateTime start = rangeFrom.atStartOfDay();
        LocalDateTime end = rangeTo.plusDays(1).atStartOfDay();

        var summary = productionRequestService.dashboardSummary(start, end, 0);
        List<WarehouseDashboardDto.StockLine> lowStock = productRepository.findByActiveTrue().stream()
                .sorted(Comparator.comparing(Product::getQuantityInStock))
                .limit(8)
                .map(p -> new WarehouseDashboardDto.StockLine(p.getId(), p.getName(), p.getUnit(), p.getQuantityInStock()))
                .toList();
        return new WarehouseDashboardDto(
                rangeFrom, rangeTo,
                orderRepository.countByStatus(OrderStatus.NEW),
                orderRepository.countByStatus(OrderStatus.READY),
                orderRepository.countByStatusInAndShippedAtBetween(
                        List.of(OrderStatus.DELIVERED, OrderStatus.COMPLETED), start, end),
                summary.counts().awaitingReceipt(),
                lowStock,
                orderRepository.findByStatusInOrderByCreatedAtAsc(List.of(OrderStatus.NEW, OrderStatus.READY))
                        .stream().map(OrderDto::summary).toList()
        );
    }
}
