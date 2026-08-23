package com.confectionery.crm.api;

import com.confectionery.crm.api.dto.OrderDto;
import com.confectionery.crm.api.dto.OrderRequest;
import com.confectionery.crm.api.dto.OrderSummaryDto;
import com.confectionery.crm.api.dto.ShipRequest;
import com.confectionery.crm.domain.OrderStatus;
import com.confectionery.crm.service.OrderService;
import com.confectionery.crm.web.dto.OrderForm;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderApiController {

    private final OrderService orderService;

    @GetMapping
    public List<OrderSummaryDto> list(@RequestParam(required = false) OrderStatus status) {
        var orders = status == null ? orderService.findAll() : orderService.findByStatus(status);
        return orders.stream().map(OrderDto::summary).toList();
    }

    @GetMapping("/{id}")
    public OrderDto get(@PathVariable Long id) {
        return OrderDto.from(orderService.findById(id));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SALES','ADMIN')")
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
    @PreAuthorize("hasAnyRole('SALES','ADMIN')")
    public OrderDto confirm(@PathVariable Long id) {
        return OrderDto.from(orderService.confirm(id));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('SALES','ADMIN')")
    public OrderDto cancel(@PathVariable Long id) {
        return OrderDto.from(orderService.cancel(id));
    }

    @PostMapping("/{id}/ship")
    @PreAuthorize("hasAnyRole('WAREHOUSE_FG','ADMIN')")
    public OrderDto ship(@PathVariable Long id, @Valid @RequestBody ShipRequest req, Authentication authentication) {
        return OrderDto.from(orderService.ship(id, req.vehicleNumber(), authentication.getName()));
    }
}
