package com.confectionery.crm.web;

import com.confectionery.crm.service.CounterpartyService;
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
import java.util.ArrayList;
import java.util.List;

/** Закупки сырья у поставщиков — см. PurchaseService. Раздел зав. склада сырья и админа. */
@Controller
@RequestMapping("/purchases")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','WAREHOUSE_RAW')")
public class PurchaseController {

    private final PurchaseService purchaseService;
    private final IngredientService ingredientService;
    private final CounterpartyService counterpartyService;

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
        model.addAttribute("counterparties", counterpartyService.findAll());
        model.addAttribute("date", filterDate);
        model.addAttribute("all", all);
        return "purchases/list";
    }

    /**
     * ingredientId/quantity/totalPrice — параллельные списки: строка формы №i задаёт одну
     * позицию закупки (несколько товаров от одного контрагента за раз, см. purchases/list.html
     * — там несколько одноимённых полей, Spring сам собирает их в списки в порядке появления
     * в форме). totalPrice — необязательное поле, пустая строка парсится вручную в null
     * (иначе Spring падает с 400 на "", как и в ProductionRequestController.substituteItem).
     */
    @PostMapping
    public String create(@RequestParam Long counterpartyId,
                          @RequestParam List<Long> ingredientId, @RequestParam List<BigDecimal> quantity,
                          @RequestParam(required = false) List<String> totalPrice,
                          @RequestParam(required = false) LocalDate purchaseDate,
                          Authentication authentication, RedirectAttributes redirectAttributes) {
        try {
            if (ingredientId.size() != quantity.size()) {
                throw new IllegalArgumentException("Данные формы повреждены — не совпадает число позиций");
            }
            List<PurchaseService.ItemInput> items = new ArrayList<>();
            for (int i = 0; i < ingredientId.size(); i++) {
                String priceText = totalPrice != null && i < totalPrice.size() ? totalPrice.get(i) : null;
                BigDecimal price = (priceText == null || priceText.isBlank()) ? null : new BigDecimal(priceText);
                items.add(new PurchaseService.ItemInput(ingredientId.get(i), quantity.get(i), price));
            }
            purchaseService.create(counterpartyId, items, purchaseDate, authentication.getName());
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/purchases";
    }

    /** Повторная ручная выгрузка в 1С — см. PurchaseService.resyncOneC. */
    @PostMapping("/{id}/sync-1c")
    @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT')")
    public String syncOneC(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            purchaseService.resyncOneC(id);
            redirectAttributes.addFlashAttribute("success", "Закупка №" + id + " синхронизирована с 1С");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Не удалось синхронизировать с 1С: " + e.getMessage());
        }
        return "redirect:/purchases";
    }
}
