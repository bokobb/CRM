package com.confectionery.crm.api;

import com.confectionery.crm.api.dto.DebtRequest;
import com.confectionery.crm.api.dto.OrderDto;
import com.confectionery.crm.api.dto.OrderItemUpdateRequest;
import com.confectionery.crm.api.dto.OrderRequest;
import com.confectionery.crm.api.dto.OrderSummaryDto;
import com.confectionery.crm.api.dto.ShipRequest;
import com.confectionery.crm.domain.Order;
import com.confectionery.crm.domain.OrderStatus;
import com.confectionery.crm.service.OrderService;
import com.confectionery.crm.web.dto.OrderForm;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderApiController {

    private final OrderService orderService;

    /** from/to не заданы — все даты (как раньше); мобильное приложение по умолчанию передаёт сегодняшний день. */
    @GetMapping
    public List<OrderSummaryDto> list(@RequestParam(required = false) OrderStatus status,
                                       @RequestParam(required = false) Long clientId,
                                       @RequestParam(required = false) LocalDate from,
                                       @RequestParam(required = false) LocalDate to) {
        List<Order> orders;
        if (clientId != null) {
            orders = orderService.findByClient(clientId);
        } else if (from != null && to != null) {
            orders = status == null ? orderService.findByDateRange(from, to)
                    : orderService.findByStatusAndDateRange(status, from, to);
        } else {
            orders = status == null ? orderService.findAll() : orderService.findByStatus(status);
        }
        return orders.stream().map(OrderDto::summary).toList();
    }

    @GetMapping("/{id}")
    public OrderDto get(@PathVariable Long id) {
        return OrderDto.from(orderService.findById(id));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SALES','ADMIN','ACCOUNTANT')")
    public OrderDto create(@Valid @RequestBody OrderRequest req) {
        OrderForm form = new OrderForm();
        form.setClientId(req.clientId());
        form.setDueDate(req.dueDate());
        form.setNotes(req.notes());
        req.items().forEach(item -> {
            OrderForm.OrderItemForm itemForm = new OrderForm.OrderItemForm();
            itemForm.setProductId(item.productId());
            itemForm.setQuantity(item.quantity());
            form.getItems().add(itemForm);
        });
        return OrderDto.from(orderService.createOrder(form));
    }

    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasAnyRole('WAREHOUSE_FG','ADMIN')")
    public OrderDto confirm(@PathVariable Long id) {
        return OrderDto.from(orderService.confirm(id));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('SALES','ADMIN','ACCOUNTANT','WAREHOUSE_FG')")
    public OrderDto cancel(@PathVariable Long id) {
        return OrderDto.from(orderService.cancel(id));
    }

    @PutMapping("/{id}/items")
    @PreAuthorize("hasAnyRole('WAREHOUSE_FG','ADMIN')")
    public OrderDto updateItems(@PathVariable Long id, @Valid @RequestBody OrderItemUpdateRequest req) {
        return OrderDto.from(orderService.updateItems(id, req.items()));
    }

    @PostMapping("/{id}/ship")
    @PreAuthorize("hasAnyRole('WAREHOUSE_FG','ADMIN')")
    public OrderDto ship(@PathVariable Long id, @Valid @RequestBody ShipRequest req, Authentication authentication) {
        return OrderDto.from(orderService.ship(id, req.vehicleNumber(), authentication.getName()));
    }

    @PostMapping("/{id}/complete")
    @PreAuthorize("hasAnyRole('ACCOUNTANT','ADMIN')")
    public OrderDto complete(@PathVariable Long id) {
        return OrderDto.from(orderService.complete(id));
    }

    /** Список неоплаченных долгов (см. также ScheduledJobs.checkOverdueDebts). */
    @GetMapping("/debts")
    @PreAuthorize("hasAnyRole('ACCOUNTANT','ADMIN')")
    public List<OrderSummaryDto> debts() {
        return orderService.findUnpaidDebts().stream().map(OrderDto::summary).toList();
    }

    @PostMapping("/{id}/debt")
    @PreAuthorize("hasAnyRole('ACCOUNTANT','ADMIN')")
    public OrderDto setDebt(@PathVariable Long id, @Valid @RequestBody DebtRequest req) {
        return OrderDto.from(orderService.setDebt(id, req.dueDate()));
    }

    @PostMapping("/{id}/debt/paid")
    @PreAuthorize("hasAnyRole('ACCOUNTANT','ADMIN')")
    public OrderDto markDebtPaid(@PathVariable Long id) {
        return OrderDto.from(orderService.markDebtPaid(id));
    }
}
