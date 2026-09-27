package com.confectionery.crm.web;

import com.confectionery.crm.service.OneCStockSyncService;
import com.confectionery.crm.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Остатки готовой продукции на складе ГП — отдельно от сырья (см. IngredientController). */
@Controller
@RequestMapping("/warehouse/stock")
@RequiredArgsConstructor
public class WarehouseStockController {

    private final ProductService productService;
    private final OneCStockSyncService oneCStockSyncService;

    /** Полуфабрикаты (Product.semiFinished) сюда не попадают — их остаток виден на странице "Сырьё". */
    @GetMapping
    public String list(Model model) {
        model.addAttribute("activePage", "warehouse-stock");
        var products = productService.findAll().stream().filter(p -> !p.isSemiFinished()).toList();
        model.addAttribute("products", products);
        model.addAttribute("productsByCategory", productService.groupByCategory(products));
        return "warehouse/stock";
    }

    /** Ручной запуск синхронизации с 1С — обычно она идёт по расписанию (crm.onec.sync-cron). */
    @PostMapping("/sync-1c")
    @PreAuthorize("hasRole('ADMIN')")
    public String syncWithOneC(RedirectAttributes redirectAttributes) {
        try {
            var result = oneCStockSyncService.sync();
            String message = "Обновлено товаров: " + result.productsUpdated() + ", сырья: " + result.ingredientsUpdated();
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
        return "redirect:/warehouse/stock";
    }
}
