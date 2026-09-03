package com.confectionery.crm.web;

import com.confectionery.crm.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/** Остатки готовой продукции на складе ГП — отдельно от сырья (см. IngredientController). */
@Controller
@RequestMapping("/warehouse/stock")
@RequiredArgsConstructor
public class WarehouseStockController {

    private final ProductService productService;

    @GetMapping
    public String list(Model model) {
        model.addAttribute("activePage", "warehouse-stock");
        var products = productService.findAll();
        model.addAttribute("products", products);
        model.addAttribute("productsByCategory", productService.groupByCategory(products));
        return "warehouse/stock";
    }
}
