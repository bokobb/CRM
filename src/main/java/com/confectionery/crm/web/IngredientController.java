package com.confectionery.crm.web;

import com.confectionery.crm.domain.Ingredient;
import com.confectionery.crm.service.IngredientService;
import com.confectionery.crm.service.OneCStockSyncService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/ingredients")
@RequiredArgsConstructor
public class IngredientController {

    private final IngredientService ingredientService;
    private final OneCStockSyncService oneCStockSyncService;

    @GetMapping
    public String list(Model model) {
        model.addAttribute("activePage", "ingredients");
        model.addAttribute("ingredients", ingredientService.findAll());
        return "ingredients/list";
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("activePage", "ingredients");
        model.addAttribute("ingredient", new Ingredient());
        return "ingredients/form";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        model.addAttribute("activePage", "ingredients");
        model.addAttribute("ingredient", ingredientService.findById(id));
        return "ingredients/form";
    }

    @PostMapping
    public String save(@Valid @ModelAttribute("ingredient") Ingredient ingredient, BindingResult binding, Model model) {
        if (binding.hasErrors()) {
            model.addAttribute("activePage", "ingredients");
            return "ingredients/form";
        }
        ingredientService.save(ingredient);
        return "redirect:/ingredients";
    }

    /** Ручной запуск синхронизации с 1С — обычно она идёт по расписанию (crm.onec.sync-cron), см. WarehouseStockController. */
    @PostMapping("/sync-1c")
    @PreAuthorize("hasRole('ADMIN')")
    public String syncWithOneC(RedirectAttributes redirectAttributes) {
        try {
            var result = oneCStockSyncService.sync();
            String message = "Обновлено сырья: " + result.ingredientsUpdated() + ", товаров: " + result.productsUpdated();
            if (result.productsDeactivated() > 0) {
                message += ". Снято с продажи (помечены на удаление в 1С): " + result.productsDeactivated();
            }
            if (!result.unmatched().isEmpty()) {
                message += ". Не сопоставлено с каталогом: " + result.unmatched().size()
                        + " (" + String.join(", ", result.unmatched()) + ")";
            }
            redirectAttributes.addFlashAttribute("success", message);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Не удалось синхронизировать с 1С: " + e.getMessage());
        }
        return "redirect:/ingredients";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            ingredientService.delete(id);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Нельзя удалить: ингредиент используется в рецептуре");
        }
        return "redirect:/ingredients";
    }
}
