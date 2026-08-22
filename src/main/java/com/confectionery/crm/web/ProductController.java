package com.confectionery.crm.web;

import com.confectionery.crm.domain.Product;
import com.confectionery.crm.service.IngredientService;
import com.confectionery.crm.service.ProductService;
import com.confectionery.crm.web.dto.RecipeForm;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@Controller
@RequestMapping("/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;
    private final IngredientService ingredientService;

    @GetMapping
    public String list(Model model) {
        model.addAttribute("activePage", "products");
        model.addAttribute("products", productService.findAll());
        return "products/list";
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("activePage", "products");
        model.addAttribute("product", new Product());
        return "products/form";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        model.addAttribute("activePage", "products");
        model.addAttribute("product", productService.findById(id));
        return "products/form";
    }

    @PostMapping
    public String save(@Valid @ModelAttribute("product") Product product, BindingResult binding, Model model) {
        if (binding.hasErrors()) {
            model.addAttribute("activePage", "products");
            return "products/form";
        }
        productService.save(product);
        return "redirect:/products";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id) {
        productService.delete(id);
        return "redirect:/products";
    }

    @GetMapping("/{id}/recipe")
    public String recipeForm(@PathVariable Long id, Model model) {
        Product product = productService.findById(id);
        var recipe = productService.findRecipe(id);

        RecipeForm form = new RecipeForm();
        if (recipe != null) {
            form.setYieldQuantity(recipe.getYieldQuantity());
            recipe.getItems().forEach(item -> {
                RecipeForm.RecipeItemForm itemForm = new RecipeForm.RecipeItemForm();
                itemForm.setIngredientId(item.getIngredient().getId());
                itemForm.setQuantity(item.getQuantity());
                form.getItems().add(itemForm);
            });
        }
        // добиваем пустыми строками, чтобы можно было добавить новые ингредиенты без JS
        while (form.getItems().size() < 10) {
            RecipeForm.RecipeItemForm blank = new RecipeForm.RecipeItemForm();
            blank.setQuantity(BigDecimal.ZERO);
            form.getItems().add(blank);
        }

        model.addAttribute("activePage", "products");
        model.addAttribute("product", product);
        model.addAttribute("recipeForm", form);
        model.addAttribute("allIngredients", ingredientService.findAll());
        return "products/recipe";
    }

    @PostMapping("/{id}/recipe")
    public String saveRecipe(@PathVariable Long id, @ModelAttribute("recipeForm") RecipeForm form) {
        productService.saveRecipe(id, form);
        return "redirect:/products/" + id + "/recipe";
    }
}
