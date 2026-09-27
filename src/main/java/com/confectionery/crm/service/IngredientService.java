package com.confectionery.crm.service;

import com.confectionery.crm.domain.Ingredient;
import com.confectionery.crm.repository.IngredientRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class IngredientService {

    private final IngredientRepository ingredientRepository;

    public List<Ingredient> findAll() {
        return ingredientRepository.findAll();
    }

    /** Без архивного сырья — для выбора в новых закупках, рецептах, заявках и списаниях. */
    public List<Ingredient> findAllActive() {
        return ingredientRepository.findByActiveTrue();
    }

    /**
     * Сырьё для НОВОЙ позиции (закупка, добавление/замена в заявке) — архивное (в т.ч. помеченное
     * в 1С на удаление) использовать нельзя, даже если клиент прислал id из закешированного списка.
     */
    public Ingredient requireActive(Ingredient ingredient) {
        if (!ingredient.isActive()) {
            throw new IllegalStateException("Сырьё «" + ingredient.getName() + "» снято с использования");
        }
        return ingredient;
    }

    public Ingredient findById(Long id) {
        return ingredientRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Ингредиент не найден: " + id));
    }

    public List<Ingredient> findLowStock() {
        return ingredientRepository.findLowStock();
    }

    public Ingredient save(Ingredient ingredient) {
        return ingredientRepository.save(ingredient);
    }

    public void delete(Long id) {
        ingredientRepository.deleteById(id);
    }
}
