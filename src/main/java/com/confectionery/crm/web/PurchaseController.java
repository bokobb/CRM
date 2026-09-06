package com.confectionery.crm.web;

import com.confectionery.crm.service.IngredientService;
import com.confectionery.crm.service.PurchaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Закупки сырья у поставщиков — см. PurchaseService. Раздел зав. склада сырья и админа. */
@Controller
@RequestMapping("/purchases")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','WAREHOUSE_RAW')")
public class PurchaseController {

    private final PurchaseService purchaseService;
    private final IngredientService ingredientService;

    /** По умолчанию показываются закупки за сегодня — {@code all=true} снимает фильтр по дате. */
    @GetMapping
    public String list(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                        @RequestParam(defaultValue = "false") boolean all,
                        Model model) {
        LocalDate filterDate = all ? null : (date != null ? date : LocalDate.now());
        model.addAttribute("activePage", "purchases");
        model.addAttribute("purchases", filterDate == null
                ? purchaseService.findAll()
                : purchaseService.findByPurchaseDateBetween(filterDate, filterDate));
        model.addAttribute("ingredients", ingredientService.findAll());
        model.addAttribute("date", filterDate);
        model.addAttribute("all", all);
        return "purchases/list";
    }

    @PostMapping
    public String create(@RequestParam Long ingredientId, @RequestParam BigDecimal quantity,
                          @RequestParam String supplier, @RequestParam(required = false) BigDecimal totalPrice,
                          @RequestParam(required = false) LocalDate purchaseDate,
                          Authentication authentication, RedirectAttributes redirectAttributes) {
        try {
            purchaseService.create(ingredientId, quantity, supplier, totalPrice, purchaseDate, authentication.getName());
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/purchases";
    }
}
