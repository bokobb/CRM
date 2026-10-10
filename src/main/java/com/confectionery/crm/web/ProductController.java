package com.confectionery.crm.web;

import com.confectionery.crm.domain.Product;
import com.confectionery.crm.service.IngredientService;
import com.confectionery.crm.service.OneCDepartmentImportService;
import com.confectionery.crm.service.OneCDepartments;
import com.confectionery.crm.service.OneCRecipeImportService;
import com.confectionery.crm.service.OneCSpecificationImportService;
import com.confectionery.crm.service.ProductPhotoService;
import com.confectionery.crm.service.ProductService;
import com.confectionery.crm.web.dto.RecipeForm;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;
    private final IngredientService ingredientService;
    private final ProductPhotoService productPhotoService;
    private final OneCRecipeImportService oneCRecipeImportService;
    private final OneCDepartmentImportService oneCDepartmentImportService;
    private final OneCSpecificationImportService oneCSpecificationImportService;

    @GetMapping
    public String list(Model model) {
        model.addAttribute("activePage", "products");
        var products = productService.findAll();
        model.addAttribute("products", products);
        model.addAttribute("productsByCategory", productService.groupByCategory(products));
        model.addAttribute("departmentNames", OneCDepartments.NAMES);
        return "products/list";
    }

    @GetMapping("/recipes")
    public String recipes(Model model) {
        model.addAttribute("activePage", "products-recipes");
        var products = productService.findAll();
        model.addAttribute("products", products);
        model.addAttribute("productsByGroup", productService.groupByGroup(products));
        return "products/recipes";
    }

    @PostMapping("/import-recipes-1c")
    @PreAuthorize("hasRole('ADMIN')")
    public String importRecipesFromOneC(RedirectAttributes redirectAttributes) {
        try {
            var result = oneCRecipeImportService.importAll();
            String message = "Импортировано рецептов: " + result.imported()
                    + ". Без спецификации в 1С: " + result.skippedNoSpec()
                    + ". Не найдено в 1С по названию: " + result.skippedNoOneCMatch();
            if (!result.warnings().isEmpty()) {
                message += ". Предупреждения: " + result.warnings().size()
                        + " (" + String.join("; ", result.warnings()) + ")";
            }
            redirectAttributes.addFlashAttribute("success", message);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Не удалось импортировать рецепты из 1С: " + e.getMessage());
        }
        return "redirect:/products/recipes";
    }

    @PostMapping("/import-departments-1c")
    @PreAuthorize("hasRole('ADMIN')")
    public String importDepartmentsFromOneC(RedirectAttributes redirectAttributes) {
        try {
            var result = oneCDepartmentImportService.importAll();
            String message = "Подразделение обновлено у товаров: " + result.updated()
                    + ". Без истории документов в 1С: " + result.skippedNoHistory()
                    + ". Не найдено в 1С по названию: " + result.skippedNoOneCMatch();
            redirectAttributes.addFlashAttribute("success", message);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Не удалось импортировать подразделения из 1С: " + e.getMessage());
        }
        return "redirect:/products/recipes";
    }

    @PostMapping("/import-specifications-1c")
    @PreAuthorize("hasRole('ADMIN')")
    public String importSpecificationsFromOneC(RedirectAttributes redirectAttributes) {
        try {
            var result = oneCSpecificationImportService.importAll();
            String message = "Спецификация обновлена у товаров: " + result.updated()
                    + ". Без спецификации в 1С: " + result.skippedNoSpec()
                    + ". Не найдено в 1С по названию: " + result.skippedNoOneCMatch();
            redirectAttributes.addFlashAttribute("success", message);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Не удалось импортировать спецификации из 1С: " + e.getMessage());
        }
        return "redirect:/products/recipes";
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("activePage", "products");
        model.addAttribute("product", new Product());
        model.addAttribute("departments", OneCDepartments.NAMES);
        return "products/form";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        model.addAttribute("activePage", "products");
        model.addAttribute("product", productService.findById(id));
        model.addAttribute("departments", OneCDepartments.NAMES);
        return "products/form";
    }

    @PostMapping
    public String save(@Valid @ModelAttribute("product") Product product, BindingResult binding, Model model,
                        @RequestParam(value = "imageFile", required = false) MultipartFile imageFile,
                        Authentication auth) {
        if (binding.hasErrors()) {
            model.addAttribute("activePage", "products");
            model.addAttribute("departments", OneCDepartments.NAMES);
            return "products/form";
        }
        // Пустой выбор в select ("Не задано") приходит как "" — normализуем в null, иначе в 1С
        // ушёл бы невалидный GUID вместо пустого значения/запасного варианта по умолчанию (см.
        // OneCProductionSyncService).
        if (product.getOneCDepartmentKey() != null && product.getOneCDepartmentKey().isBlank()) {
            product.setOneCDepartmentKey(null);
        }
        // Форма передаёт только редактируемые поля — новый товар сохраняем как есть, а у
        // существующего переносим их в загруженную из БД сущность. Иначе всё, чего в форме нет
        // (остаток, связь с 1С, архив, скрытие от клиентов), затиралось бы значениями по умолчанию.
        Product saved = productService.save(product.getId() == null ? product : applyForm(product));
        // Фото товара меняет только админ (альбом на сайте, см. ProductPhotoService) — у остальных файл игнорируется.
        boolean admin = auth != null && auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        if (admin && imageFile != null && !imageFile.isEmpty()) {
            productPhotoService.replaceCover(saved.getId(), imageFile);
        }
        return "redirect:/products";
    }

    private Product applyForm(Product form) {
        Product product = productService.findById(form.getId());
        product.setName(form.getName());
        product.setCategory(form.getCategory());
        product.setPrice(form.getPrice());
        product.setGroup(form.getGroup());
        product.setUnit(form.getUnit());
        product.setNetWeightGrams(form.getNetWeightGrams());
        product.setMassReferenceProduct(form.isMassReferenceProduct());
        product.setOneCDepartmentKey(form.getOneCDepartmentKey());
        product.setDescription(form.getDescription());
        product.setSemiFinished(form.isSemiFinished());
        // С альбомом обложку задаёт первое фото (ProductPhotoService), ссылку из формы берём только у товара без фото.
        if (product.getPhotos().isEmpty()) {
            product.setImageUrl(form.getImageUrl());
        }
        return product;
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

    /** Скрыть от клиентов (витрина, WhatsApp) — until пустой: пока не вернут вручную. См. Product#hiddenFromClients. */
    @PostMapping("/{id}/hide-from-clients")
    @PreAuthorize("hasRole('ADMIN')")
    public String hideFromClients(@PathVariable Long id,
                                  @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(
                                          iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
                                  java.time.LocalDate until,
                                  RedirectAttributes redirectAttributes) {
        try {
            productService.setHiddenFromClients(id, true, until);
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/products";
    }

    @PostMapping("/{id}/show-to-clients")
    @PreAuthorize("hasRole('ADMIN')")
    public String showToClients(@PathVariable Long id) {
        productService.setHiddenFromClients(id, false, null);
        return "redirect:/products";
    }

    @PostMapping("/{id}/restore")
    @PreAuthorize("hasRole('ADMIN')")
    public String restore(@PathVariable Long id) {
        productService.restore(id);
        return "redirect:/products";
    }

    @GetMapping("/{id}/recipe")
    public String recipeForm(@PathVariable Long id, @RequestParam(required = false) Long copyFrom, Model model) {
        Product product = productService.findById(id);
        // copyFrom явно задан — показываем (ещё не сохранённую) рецептуру ДРУГОГО товара как
        // черновик для текущего; иначе — собственную рецептуру товара, если она уже есть.
        var recipe = copyFrom != null ? productService.findRecipe(copyFrom) : productService.findRecipe(id);

        RecipeForm form = new RecipeForm();
        if (recipe != null) {
            form.setYieldQuantity(recipe.getYieldQuantity());
            recipe.getItems().forEach(item -> {
                RecipeForm.RecipeItemForm itemForm = new RecipeForm.RecipeItemForm();
                itemForm.setIngredientId(item.getIngredient().getId());
                itemForm.setQuantity(item.getQuantity());
                itemForm.setIngredientLabel(item.getIngredient().getName() + " (" + item.getIngredient().getUnit() + ")");
                form.getItems().add(itemForm);
            });
        }

        model.addAttribute("activePage", "products");
        model.addAttribute("product", product);
        model.addAttribute("recipeForm", form);
        model.addAttribute("allIngredients", ingredientService.findAllActive());
        model.addAttribute("copyableProducts", productService.findProductsWithRecipe(id));
        model.addAttribute("copyFromId", copyFrom);
        return "products/recipe";
    }

    @PostMapping("/{id}/recipe")
    public String saveRecipe(@PathVariable Long id, @ModelAttribute("recipeForm") RecipeForm form) {
        productService.saveRecipe(id, form);
        return "redirect:/products/" + id + "/recipe";
    }
}
