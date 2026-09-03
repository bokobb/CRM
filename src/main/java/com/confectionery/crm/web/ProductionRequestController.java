package com.confectionery.crm.web;

import com.confectionery.crm.service.ProductionRequestService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Заявки на сырьё под производство: бухгалтер создаёт -> зав. склада сырья собирает по
 * чек-листу (списывает сырьё) -> зав. склада ГП оприходует фактический выход готовой
 * продукции. См. ProductionRequestService — вся бизнес-логика и переиспользование
 * StockService там; контроллер только маршрутизирует роли к нужным действиям.
 */
@Controller
@RequestMapping("/production-requests")
@RequiredArgsConstructor
public class ProductionRequestController {

    private final ProductionRequestService requestService;

    @GetMapping
    public String list(Model model) {
        model.addAttribute("activePage", "production-requests");
        model.addAttribute("requests", requestService.findAll());
        return "production-requests/list";
    }

    @GetMapping("/new")
    @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT')")
    public String newForm(Model model) {
        model.addAttribute("activePage", "production-requests");
        model.addAttribute("products", requestService.productsWithRecipe());
        return "production-requests/new";
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT')")
    public String create(@RequestParam Long productId, @RequestParam BigDecimal batches,
                          Authentication authentication, RedirectAttributes redirectAttributes) {
        try {
            var request = requestService.create(productId, batches, authentication.getName());
            return "redirect:/production-requests/" + request.getId();
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/production-requests/new";
        }
    }

    @GetMapping("/{id}")
    public String view(@PathVariable Long id, Model model) {
        model.addAttribute("activePage", "production-requests");
        model.addAttribute("request", requestService.findById(id));
        return "production-requests/view";
    }

    @PostMapping("/{id}/collect")
    @PreAuthorize("hasAnyRole('ADMIN','WAREHOUSE_RAW')")
    public String collect(@PathVariable Long id, @RequestParam(required = false) List<Long> itemIds,
                           Authentication authentication, RedirectAttributes redirectAttributes) {
        Set<Long> confirmed = itemIds == null ? new HashSet<>() : new HashSet<>(itemIds);
        try {
            requestService.collect(id, confirmed, authentication.getName());
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/production-requests/" + id;
    }

    @PostMapping("/{id}/receive")
    @PreAuthorize("hasAnyRole('ADMIN','WAREHOUSE_FG')")
    public String receive(@PathVariable Long id, @RequestParam BigDecimal quantity,
                           Authentication authentication, RedirectAttributes redirectAttributes) {
        try {
            requestService.receive(id, quantity, authentication.getName());
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/production-requests/" + id;
    }
}
