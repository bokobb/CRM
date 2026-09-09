package com.confectionery.crm.api;

import com.confectionery.crm.api.dto.*;
import com.confectionery.crm.domain.Product;
import com.confectionery.crm.service.FileStorageService;
import com.confectionery.crm.service.IngredientService;
import com.confectionery.crm.service.ProductService;
import com.confectionery.crm.web.dto.RecipeForm;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductApiController {

    private final ProductService productService;
    private final IngredientService ingredientService;
    private final FileStorageService fileStorageService;

    /**
     * Без архивных — используется мобильным приложением и для поиска товара при оформлении
     * заказа (по умолчанию, полуфабрикаты скрыты — их нельзя заказать), и для экранов
     * управления товарами/рецептами ({@code includeSemiFinished=true} — там полуфабрикат
     * нужно видеть и редактировать, см. ProductService.findAllActiveForManagement).
     */
    @GetMapping
    public List<ProductDto> list(@RequestParam(defaultValue = "false") boolean includeSemiFinished) {
        var products = includeSemiFinished ? productService.findAllActiveForManagement() : productService.findAllActive();
        return products.stream().map(ProductDto::from).toList();
    }

    @GetMapping("/{id}")
    public ProductDto get(@PathVariable Long id) {
        return ProductDto.from(productService.findById(id));
    }

    @PostMapping
    public ProductDto create(@Valid @RequestBody ProductRequest req) {
        return ProductDto.from(productService.save(toEntity(new Product(), req)));
    }

    @PutMapping("/{id}")
    public ProductDto update(@PathVariable Long id, @Valid @RequestBody ProductRequest req) {
        Product product = productService.findById(id);
        return ProductDto.from(productService.save(toEntity(product, req)));
    }

    /** Только ADMIN — товар архивируется вместо удаления, если уже есть история (см. ProductService.delete). */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable Long id) {
        productService.delete(id);
    }

    @PostMapping("/{id}/image")
    public ProductDto uploadImage(@PathVariable Long id, @RequestParam("file") MultipartFile file) {
        Product product = productService.findById(id);
        product.setImageUrl(fileStorageService.storeProductImage(id, file));
        return ProductDto.from(productService.save(product));
    }

    @GetMapping("/{id}/recipe")
    public RecipeDto getRecipe(@PathVariable Long id) {
        productService.findById(id);
        return RecipeDto.from(productService.findRecipe(id));
    }

    @PutMapping("/{id}/recipe")
    public RecipeDto saveRecipe(@PathVariable Long id, @RequestBody RecipeRequest req) {
        RecipeForm form = new RecipeForm();
        form.setYieldQuantity(req.yieldQuantity());
        req.items().forEach(item -> {
            RecipeForm.RecipeItemForm itemForm = new RecipeForm.RecipeItemForm();
            itemForm.setIngredientId(item.ingredientId());
            itemForm.setQuantity(item.quantity());
            form.getItems().add(itemForm);
        });
        productService.saveRecipe(id, form);
        return RecipeDto.from(productService.findRecipe(id));
    }

    private Product toEntity(Product product, ProductRequest req) {
        product.setName(req.name());
        product.setCategory(req.category());
        product.setGroup(req.group());
        product.setDescription(req.description());
        product.setPrice(req.price());
        product.setUnit(req.unit());
        product.setImageUrl(req.imageUrl());
        product.setSemiFinished(req.semiFinished());
        return product;
    }
}
