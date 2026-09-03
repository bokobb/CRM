package com.confectionery.crm.web;

import com.confectionery.crm.domain.Product;
import com.confectionery.crm.service.FileStorageService;
import com.confectionery.crm.service.IngredientService;
import com.confectionery.crm.service.ProductService;
import com.confectionery.crm.web.dto.RecipeForm;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;

@Controller
@RequestMapping("/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;
    private final IngredientService ingredientService;
    private final FileStorageService fileStorageService;

    @GetMapping
    public String list(Model model) {
        model.addAttribute("activePage", "products");
        var products = productService.findAll();
        model.addAttribute("products", products);
        model.addAttribute("productsByCategory", productService.groupByCategory(products));
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
    public String save(@Valid @ModelAttribute("product") Product product, BindingResult binding, Model model,
                        @RequestParam(value = "imageFile", required = false) MultipartFile imageFile) {
        if (binding.hasErrors()) {
            model.addAttribute("activePage", "products");
            return "products/form";
        }
        Product saved = productService.save(product);
        if (imageFile != null && !imageFile.isEmpty()) {
            saved.setImageUrl(fileStorageService.storeProductImage(saved.getId(), imageFile));
            productService.save(saved);
        }
        return "redirect:/products";
    }

    @PostMapping("/{id}/delete")
    @PreAuthorize("hasRole('ADMIN')")
    public String delete(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        if (productService.delete(id) == ProductService.DeleteOutcome.ARCHIVED) {
            redirectAttributes.addFlashAttribute("info",
                    "Товар нельзя удалить полностью — он уже встречался в заказах/на складе. Он архивирован: пропал из каталога для новых заказов, старые заказы не затронуты.");
        }
        return "redirect:/products";
    }

    @PostMapping("/{id}/restore")
    @PreAuthorize("hasRole('ADMIN')")
    public String restore(@PathVariable Long id) {
        productService.restore(id);
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
