package com.confectionery.crm.api;

import com.confectionery.crm.api.dto.*;
import com.confectionery.crm.domain.Product;
import com.confectionery.crm.service.ProductPhotoService;
import com.confectionery.crm.service.IngredientService;
import com.confectionery.crm.service.OneCDepartmentImportService;
import com.confectionery.crm.service.OneCRecipeImportService;
import com.confectionery.crm.service.ProductService;
import com.confectionery.crm.web.dto.RecipeForm;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductApiController {

    private final ProductService productService;
    private final com.confectionery.crm.service.OrderPriceFillService orderPriceFillService;
    private final IngredientService ingredientService;
    private final ProductPhotoService productPhotoService;
    private final OneCDepartmentImportService oneCDepartmentImportService;
    private final OneCRecipeImportService oneCRecipeImportService;

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
        ProductDto saved = ProductDto.from(productService.save(toEntity(product, req)));
        orderPriceFillService.afterPriceChange(null, id);   // нули в неотгруженных заказах с этим товаром
        return saved;
    }

    /** Только ADMIN — товар архивируется вместо удаления, если уже есть история (см. ProductService.delete). */
    /** Скрыть товар от клиентов (витрина, WhatsApp) или вернуть — только ADMIN, см. Product#hiddenFromClients. */
    @PutMapping("/{id}/client-visibility")
    @PreAuthorize("hasRole('ADMIN')")
    public ProductDto setClientVisibility(@PathVariable Long id, @RequestBody ClientVisibilityRequest req) {
        return ProductDto.from(productService.setHiddenFromClients(id, req.hidden(), req.until()));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable Long id) {
        productService.delete(id);
    }

    /** Старое «одно фото на товар» (приложение до альбома): заменяет обложку, остальные фото альбома остаются. */
    @PostMapping("/{id}/image")
    @PreAuthorize("hasRole('ADMIN')")
    public ProductDto uploadImage(@PathVariable Long id, @RequestParam("file") MultipartFile file) {
        return ProductDto.from(productPhotoService.replaceCover(id, file));
    }

    /** Альбом товара для сайта: фото сколько угодно, добавляются в конец; первое — обложка. */
    @PostMapping("/{id}/photos")
    @PreAuthorize("hasRole('ADMIN')")
    public ProductDto addPhotos(@PathVariable Long id, @RequestParam("files") List<MultipartFile> files) {
        return ProductDto.from(productPhotoService.add(id, files));
    }

    @DeleteMapping("/{id}/photos/{photoId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ProductDto deletePhoto(@PathVariable Long id, @PathVariable Long photoId) {
        return ProductDto.from(productPhotoService.delete(id, photoId));
    }

    /** Все фото товара в новом порядке, первое станет обложкой. */
    @PutMapping("/{id}/photos/order")
    @PreAuthorize("hasRole('ADMIN')")
    public ProductDto reorderPhotos(@PathVariable Long id, @RequestBody List<Long> photoIds) {
        return ProductDto.from(productPhotoService.reorder(id, photoIds));
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
        // С альбомом обложку задаёт первое фото (ProductPhotoService); старое приложение шлёт прежний
        // imageUrl при каждом сохранении — у товара с фото его не слушаем, иначе обложка бы затёрлась.
        if (product.getPhotos().isEmpty()) {
            product.setImageUrl(req.imageUrl());
        }
        product.setSemiFinished(req.semiFinished());
        product.setNetWeightGrams(req.netWeightGrams());
        product.setMassReferenceProduct(req.massReferenceProduct());
        product.setUnitWeightKg(positiveOrNull(req.unitWeightKg()));
        product.setUnitsPerBox(positiveOrNull(req.unitsPerBox()));
        product.setBoxVolumeM3(positiveOrNull(req.boxVolumeM3()));
        product.setUnitsPerBox2(positiveOrNull(req.unitsPerBox2()));
        product.setBoxVolume2M3(positiveOrNull(req.boxVolume2M3()));
        // "" из выпадающего списка ("Не задано") означает то же самое, что и отсутствие поля —
        // нормализуем в null, иначе в 1С ушёл бы невалидный GUID вместо запасного варианта по
        // умолчанию (см. OneCProductionSyncService).
        String departmentKey = req.oneCDepartmentKey();
        product.setOneCDepartmentKey(departmentKey == null || departmentKey.isBlank() ? null : departmentKey);
        return product;
    }

    private static BigDecimal positiveOrNull(BigDecimal v) {
        return v == null || v.signum() <= 0 ? null : v;
    }

    /** Только ADMIN — то же самое, что кнопка "Импортировать подразделения из 1С" в веб-версии. */
    @PostMapping("/import-departments-1c")
    @PreAuthorize("hasRole('ADMIN')")
    public OneCDepartmentImportService.ImportResult importDepartmentsFromOneC() {
        return oneCDepartmentImportService.importAll();
    }

    /** Только ADMIN — рецепты ВСЕХ товаров из 1С разом (кнопка-облако на экране «Рецепты»). */
    @PostMapping("/import-recipes-1c")
    @PreAuthorize("hasRole('ADMIN')")
    public OneCRecipeImportService.ImportResult importRecipesFromOneC() {
        return oneCRecipeImportService.importAll();
    }

    /**
     * Только ADMIN — заполняет рецепт (состав сырья) и GUID спецификации ОДНОГО товара из 1С,
     * не трогая остальные (см. OneCRecipeImportService.importOne) — кнопка "Импортировать рецепт
     * из 1С" на экране рецептуры этого товара в мобильном приложении.
     */
    @PostMapping("/{id}/import-recipe-1c")
    @PreAuthorize("hasRole('ADMIN')")
    public RecipeDto importRecipeFromOneC(@PathVariable Long id) {
        oneCRecipeImportService.importOne(id);
        return RecipeDto.from(productService.findRecipe(id));
    }
}
