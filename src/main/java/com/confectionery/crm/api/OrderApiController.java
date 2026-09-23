package com.confectionery.crm.api;

import com.confectionery.crm.api.dto.DebtRequest;
import com.confectionery.crm.api.dto.OrderDto;
import com.confectionery.crm.api.dto.OrderItemUpdateRequest;
import com.confectionery.crm.api.dto.OrderRequest;
import com.confectionery.crm.api.dto.OrderSummaryDto;
import com.confectionery.crm.api.dto.ShipRequest;
import com.confectionery.crm.domain.Order;
import com.confectionery.crm.domain.OrderStatus;
import com.confectionery.crm.service.OneCInvoiceService;
import com.confectionery.crm.service.OrderService;
import com.confectionery.crm.web.dto.OrderForm;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
    private final OneCInvoiceService oneCInvoiceService;

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

    /**
     * Накладная, приложенная вручную к документу заказа в 1С (см. OneCInvoiceService) — 404,
     * если её там ещё нет (заказ не выгружен, 1С недоступна или файл ещё не приложили);
     * мобильное приложение в этом случае показывает свою локально сгенерированную накладную.
     */
    @GetMapping("/{id}/invoice")
    public ResponseEntity<byte[]> invoice(@PathVariable Long id) {
        Order order = orderService.findById(id);
        OneCInvoiceService.InvoiceFile file = oneCInvoiceService.findInvoiceFile(order);
        if (file == null) {
            return ResponseEntity.notFound().build();
        }
        MediaType mediaType = switch (file.extension().toLowerCase()) {
            case "pdf" -> MediaType.APPLICATION_PDF;
            case "jpg", "jpeg" -> MediaType.IMAGE_JPEG;
            case "png" -> MediaType.IMAGE_PNG;
            default -> MediaType.APPLICATION_OCTET_STREAM;
        };
        String filename = file.name() + "." + file.extension();
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(file.data());
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

    /** Вернуть заказ READY -> NEW, чтобы редактировать состав чек-листом сборки (см. OrderService.reopenForEditing). */
    @PostMapping("/{id}/reopen")
    @PreAuthorize("hasAnyRole('SALES','ADMIN','ACCOUNTANT','WAREHOUSE_FG')")
    public OrderDto reopen(@PathVariable Long id) {
        return OrderDto.from(orderService.reopenForEditing(id));
    }

    /** Отмена: документ в 1С распроводится (черновик); отгруженный заказ — только админ/бухгалтер. */
    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('SALES','ADMIN','ACCOUNTANT','WAREHOUSE_FG')")
    public OrderDto cancel(@PathVariable Long id, Authentication authentication) {
        boolean privileged = authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN") || a.getAuthority().equals("ROLE_ACCOUNTANT"));
        return OrderDto.from(orderService.cancel(id, privileged));
    }

    /** Удаление отменённого заказа: документ в 1С помечается на удаление. */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT')")
    public void delete(@PathVariable Long id) {
        orderService.delete(id);
    }

    @PutMapping("/{id}/items")
    @PreAuthorize("hasAnyRole('WAREHOUSE_FG','ADMIN','ACCOUNTANT')")
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
