package com.confectionery.crm.web;

import com.confectionery.crm.domain.OrderStatus;
import com.confectionery.crm.service.ClientService;
import com.confectionery.crm.service.OneCOrderSyncService;
import com.confectionery.crm.service.OrderService;
import com.confectionery.crm.service.ProductService;
import com.confectionery.crm.service.ProductionTaskService;
import com.confectionery.crm.web.dto.OrderForm;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDate;

@Controller
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    private final ClientService clientService;
    private final ProductService productService;
    private final ProductionTaskService productionTaskService;
    private final OneCOrderSyncService oneCOrderSyncService;

    /** from/to не заданы — все даты; ссылки с дашборда передают диапазон явно. */
    @GetMapping
    public String list(@RequestParam(required = false) OrderStatus status,
                        @RequestParam(required = false) LocalDate from,
                        @RequestParam(required = false) LocalDate to,
                        Model model) {
        var orders = (from != null && to != null)
                ? (status == null ? orderService.findByDateRange(from, to) : orderService.findByStatusAndDateRange(status, from, to))
                : (status == null ? orderService.findAll() : orderService.findByStatus(status));
        model.addAttribute("activePage", "orders");
        model.addAttribute("orders", orders);
        model.addAttribute("statuses", OrderStatus.values());
        model.addAttribute("selectedStatus", status);
        model.addAttribute("rangeFrom", from);
        model.addAttribute("rangeTo", to);
        return "orders/list";
    }

    /**
     * Строк сразу видно 6, остальные 14 — заранее отрендерены, но скрыты (display:none) и
     * открываются по одной кнопкой "+ Добавить позицию" (см. orders/form.html, чистый JS
     * без перезагрузки страницы). Так проще и надёжнее, чем клиентский рендеринг новой
     * строки с индексами полей — здесь индексы уже правильно расставлены Thymeleaf'ом.
     */
    @GetMapping("/new")
    public String newForm(Model model) {
        OrderForm form = new OrderForm();
        for (int i = 0; i < 20; i++) {
            OrderForm.OrderItemForm item = new OrderForm.OrderItemForm();
            item.setQuantity(BigDecimal.ZERO);
            form.getItems().add(item);
        }
        model.addAttribute("activePage", "orders");
        model.addAttribute("orderForm", form);
        model.addAttribute("visibleItemRows", 6);
        model.addAttribute("clients", clientService.findAll());
        model.addAttribute("products", productService.findAllActive());
        return "orders/form";
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SALES','ADMIN','ACCOUNTANT')")
    public String create(@ModelAttribute("orderForm") OrderForm form, RedirectAttributes redirectAttributes) {
        try {
            var order = orderService.createOrder(form);
            return "redirect:/orders/" + order.getId();
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/orders/new";
        }
    }

    @GetMapping("/{id}")
    public String view(@PathVariable Long id, Model model) {
        model.addAttribute("activePage", "orders");
        model.addAttribute("order", orderService.findById(id));
        return "orders/view";
    }

    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasAnyRole('WAREHOUSE_FG','ADMIN')")
    public String confirm(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            orderService.confirm(id);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/orders/" + id;
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('SALES','ADMIN','ACCOUNTANT','WAREHOUSE_FG')")
    public String cancel(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            orderService.cancel(id);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/orders/" + id;
    }

    @PostMapping("/{id}/deliver")
    @PreAuthorize("hasAnyRole('WAREHOUSE_FG','ADMIN')")
    public String deliver(@PathVariable Long id,
                           @RequestParam(required = false, defaultValue = "") String vehicleNumber,
                           RedirectAttributes redirectAttributes) {
        try {
            orderService.ship(id, vehicleNumber, "веб-интерфейс");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/orders/" + id;
    }

    @PostMapping("/{id}/complete")
    @PreAuthorize("hasAnyRole('ACCOUNTANT','ADMIN')")
    public String complete(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            orderService.complete(id);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/orders/" + id;
    }

    @PostMapping("/{id}/debt")
    @PreAuthorize("hasAnyRole('ACCOUNTANT','ADMIN')")
    public String setDebt(@PathVariable Long id, @RequestParam LocalDate dueDate,
                           RedirectAttributes redirectAttributes) {
        try {
            orderService.setDebt(id, dueDate);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/orders/" + id;
    }

    @PostMapping("/{id}/debt/paid")
    @PreAuthorize("hasAnyRole('ACCOUNTANT','ADMIN')")
    public String markDebtPaid(@PathVariable Long id) {
        orderService.markDebtPaid(id);
        return "redirect:/orders/" + id;
    }

    /**
     * Ручной запуск того же прохода, что и по расписанию в 20:00 (см.
     * OneCOrderSyncService.scheduledPostEligibleOrders) — для проверки, не дожидаясь времени.
     */
    @PostMapping("/post-1c")
    @PreAuthorize("hasRole('ADMIN')")
    public String postOneC(RedirectAttributes redirectAttributes) {
        try {
            var result = oneCOrderSyncService.postEligibleOrders();
            String message = "Проведено в 1С: " + result.posted() + ", уже было проведено: " + result.alreadyPosted();
            if (!result.failed().isEmpty()) {
                message += ". Не удалось: " + String.join("; ", result.failed());
            }
            redirectAttributes.addFlashAttribute("success", message);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Не удалось запустить проведение в 1С: " + e.getMessage());
        }
        return "redirect:/orders";
    }
}
