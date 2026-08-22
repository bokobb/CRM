package com.confectionery.crm.web;

import com.confectionery.crm.domain.OrderStatus;
import com.confectionery.crm.service.ClientService;
import com.confectionery.crm.service.OrderService;
import com.confectionery.crm.service.ProductService;
import com.confectionery.crm.service.ProductionTaskService;
import com.confectionery.crm.web.dto.OrderForm;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;

@Controller
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    private final ClientService clientService;
    private final ProductService productService;
    private final ProductionTaskService productionTaskService;

    @GetMapping
    public String list(@RequestParam(required = false) OrderStatus status, Model model) {
        model.addAttribute("activePage", "orders");
        model.addAttribute("orders", status == null ? orderService.findAll() : orderService.findByStatus(status));
        model.addAttribute("statuses", OrderStatus.values());
        model.addAttribute("selectedStatus", status);
        return "orders/list";
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        OrderForm form = new OrderForm();
        for (int i = 0; i < 6; i++) {
            OrderForm.OrderItemForm item = new OrderForm.OrderItemForm();
            item.setQuantity(BigDecimal.ZERO);
            form.getItems().add(item);
        }
        model.addAttribute("activePage", "orders");
        model.addAttribute("orderForm", form);
        model.addAttribute("clients", clientService.findAll());
        model.addAttribute("products", productService.findAll());
        return "orders/form";
    }

    @PostMapping
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
    public String confirm(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            orderService.confirm(id);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/orders/" + id;
    }

    @PostMapping("/{id}/cancel")
    public String cancel(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            orderService.cancel(id);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/orders/" + id;
    }

    @PostMapping("/{id}/deliver")
    public String deliver(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            orderService.markDelivered(id);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/orders/" + id;
    }
}
