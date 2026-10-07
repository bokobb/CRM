package com.confectionery.crm.api;

import com.confectionery.crm.api.dto.IngredientDto;
import com.confectionery.crm.api.dto.IngredientRequest;
import com.confectionery.crm.domain.Ingredient;
import com.confectionery.crm.service.IngredientService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/ingredients")
@RequiredArgsConstructor
public class IngredientApiController {

    private final IngredientService ingredientService;

    @GetMapping
    public List<IngredientDto> list() {
        return ingredientService.findAllActive().stream().map(IngredientDto::from).toList();
    }

    @GetMapping("/{id}")
    public IngredientDto get(@PathVariable Long id) {
        return IngredientDto.from(ingredientService.findById(id));
    }

    @PostMapping
    public IngredientDto create(@Valid @RequestBody IngredientRequest req) {
        return IngredientDto.from(ingredientService.save(toEntity(new Ingredient(), req)));
    }

    @PutMapping("/{id}")
    public IngredientDto update(@PathVariable Long id, @Valid @RequestBody IngredientRequest req) {
        Ingredient ingredient = ingredientService.findById(id);
        return IngredientDto.from(ingredientService.save(toEntity(ingredient, req)));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        ingredientService.delete(id);
    }

    private Ingredient toEntity(Ingredient ingredient, IngredientRequest req) {
        ingredient.setName(req.name());
        ingredient.setUnit(req.unit());
        ingredient.setQuantityInStock(req.quantityInStock());
        ingredient.setMinThreshold(req.minThreshold());
        return ingredient;
    }
}
