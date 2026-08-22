package com.confectionery.crm.web;

import com.confectionery.crm.domain.Ingredient;
import com.confectionery.crm.service.IngredientService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
