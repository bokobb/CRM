package com.confectionery.crm.admin;

import com.confectionery.crm.domain.Ingredient;
import com.confectionery.crm.domain.Product;
import com.confectionery.crm.domain.Recipe;
import com.confectionery.crm.domain.RecipeItem;
import com.confectionery.crm.repository.ProductRepository;
import com.confectionery.crm.repository.RecipeRepository;
import com.confectionery.crm.service.IngredientService;
import com.confectionery.crm.service.ProductService;
import com.confectionery.crm.web.dto.RecipeForm;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/** Рецепты: список изделий с рецептурой и редактор рецептуры (экран владельца «Рецептура» в цветах админки). */
@Controller
@RequestMapping("/admin/recipes")
@RequiredArgsConstructor
public class AdminRecipeController {

    private final ProductRepository productRepository;
    private final RecipeRepository recipeRepository;
    private final ProductService productService;
    private final IngredientService ingredientService;
    private final AdminFormat fmt;

    public record Row(Product product, String group, boolean pf, Recipe recipe, BigDecimal yield, BigDecimal total,
                      BigDecimal perPiece, int items) {
    }

    public record Chip(String key, String label, int count, boolean on, String href) {
    }

    private static String group(Product p) {
        return p.getGroup() == null || p.getGroup().isBlank() ? "Без группы" : p.getGroup().trim();
    }

    @GetMapping
    @Transactional(readOnly = true)
    public String list(@RequestParam(defaultValue = "all") String group, @RequestParam(defaultValue = "") String q,
                       @RequestParam(defaultValue = "false") boolean empty, Model model) {
        Map<Long, Recipe> recipes = new HashMap<>();
        for (Recipe r : recipeRepository.findAll()) recipes.put(r.getProduct().getId(), r);
        List<Product> products = new ArrayList<>(productRepository.findByActiveTrue());
        products.sort(Comparator.comparing(Product::getName, String.CASE_INSENSITIVE_ORDER));
        String nq = AdminOrderController.norm(q);
        List<Row> base = new ArrayList<>();
        int emptyCount = 0;
        for (Product p : products) {
            Recipe r = recipes.get(p.getId());
            int items = r == null ? 0 : r.getItems().size();
            if (items == 0) emptyCount++;
            if (!nq.isEmpty() && !AdminOrderController.norm(p.getName()).contains(nq)) continue;
            if (empty && items > 0) continue;
            BigDecimal total = BigDecimal.ZERO;
            if (r != null) for (RecipeItem i : r.getItems()) total = total.add(i.getQuantity());
            BigDecimal yield = r == null ? null : r.getYieldQuantity();
            BigDecimal per = yield != null && yield.signum() > 0 && total.signum() > 0
                    ? total.multiply(BigDecimal.valueOf(1000)).divide(yield, 1, RoundingMode.HALF_UP) : null;
            base.add(new Row(p, group(p), p.isSemiFinished(), r, yield, total, per, items));
        }
        Map<String, Integer> counts = new TreeMap<>();
        for (Row r : base) counts.merge(r.group(), 1, Integer::sum);
        List<Chip> chips = new ArrayList<>();
        chips.add(new Chip("all", "Все", base.size(), group.equals("all"), href("all", q, empty)));
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            chips.add(new Chip(e.getKey(), e.getKey(), e.getValue(), group.equals(e.getKey()), href(e.getKey(), q, empty)));
        }
        if (!group.equals("all") && !counts.containsKey(group)) chips.add(new Chip(group, group, 0, true, href(group, q, empty)));
        List<Row> rows = base.stream().filter(r -> group.equals("all") || r.group().equals(group)).toList();
        model.addAttribute("rows", rows);
        model.addAttribute("chips", chips);
        model.addAttribute("q", q);
        model.addAttribute("group", group);
        model.addAttribute("empty", empty);
        model.addAttribute("total", products.size());
        model.addAttribute("emptyCount", emptyCount);
        model.addAttribute("title", "Рецепты");
        model.addAttribute("sub", "сырьё на один замес и выход в штуках");
        model.addAttribute("sec", "recipes");
        return "admin/recipes/list";
    }

    private static String href(String group, String q, boolean empty) {
        StringBuilder sb = new StringBuilder("/admin/recipes?group=")
                .append(java.net.URLEncoder.encode(group, java.nio.charset.StandardCharsets.UTF_8));
        if (!q.isEmpty()) sb.append("&q=").append(java.net.URLEncoder.encode(q, java.nio.charset.StandardCharsets.UTF_8));
        if (empty) sb.append("&empty=true");
        return sb.toString();
    }

    @GetMapping("/{productId}")
    @Transactional(readOnly = true)
    public String edit(@PathVariable Long productId, Model model) {
        model.addAttribute("title", "Рецепты");
        model.addAttribute("sec", "recipes");
        model.addAttribute("viewClass", "is-rx");
        Product p = productRepository.findById(productId).orElse(null);
        if (p == null) {
            model.addAttribute("sub", "");
            model.addAttribute("missing", true);
            return "admin/recipes/edit";
        }
        model.addAttribute("sub", p.getName());
        Recipe r = recipeRepository.findByProductId(productId).orElse(null);
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("id", p.getId());
        state.put("name", p.getName());
        state.put("yield", r == null || r.getItems().isEmpty() ? "" : r.getYieldQuantity().setScale(0, RoundingMode.DOWN).toPlainString());
        List<Map<String, Object>> rows = new ArrayList<>();
        if (r != null) {
            for (RecipeItem i : r.getItems()) {
                rows.add(Map.of("ing", i.getIngredient().getId(), "text", i.getIngredient().getName(), "qty", fmt.fix(i.getQuantity(), 3)));
            }
        }
        state.put("rows", rows);
        List<Map<String, Object>> mats = new ArrayList<>();
        for (Ingredient i : ingredientService.findAllActive()) {
            mats.add(Map.of("key", i.getId(), "label", i.getName(), "unit", i.getUnit() == null ? "кг" : i.getUnit()));
        }
        if (r != null) {   // сырьё в рецептуре, которое уже снято с учёта, тоже должно находиться
            for (RecipeItem i : r.getItems()) {
                if (mats.stream().noneMatch(m -> m.get("key").equals(i.getIngredient().getId()))) {
                    mats.add(Map.of("key", i.getIngredient().getId(), "label", i.getIngredient().getName(),
                            "unit", i.getIngredient().getUnit() == null ? "кг" : i.getIngredient().getUnit()));
                }
            }
        }
        model.addAttribute("p", p);
        model.addAttribute("pf", p.isSemiFinished());
        model.addAttribute("groupFull", group(p));
        model.addAttribute("recipe", r);
        model.addAttribute("stateJson", AdminJson.of(state));
        model.addAttribute("matsJson", AdminJson.of(mats));
        return "admin/recipes/edit";
    }

    public record SaveRow(Long ing, String qty) {
    }

    public record SaveRequest(String yield, List<SaveRow> rows) {
    }

    /** Сохранить рецептуру (JSON из recipe.js). Проверки — те же, что на странице. */
    @PostMapping(value = "/{productId}", consumes = "application/json")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> save(@PathVariable Long productId, @RequestBody SaveRequest body) {
        String y = body.yield() == null ? "" : body.yield().trim();
        if (!y.matches("\\d{1,6}") || Integer.parseInt(y) <= 0) {
            return ResponseEntity.badRequest().body(Map.of("message", "Заполните выделенные поля: сырьё из справочника и количество."));
        }
        RecipeForm form = new RecipeForm();
        form.setYieldQuantity(new BigDecimal(y));
        List<RecipeForm.RecipeItemForm> items = new ArrayList<>();
        for (SaveRow r : body.rows() == null ? List.<SaveRow>of() : body.rows()) {
            String q = r.qty() == null ? "" : r.qty().replace(" ", "").replace(',', '.');
            BigDecimal qty;
            try {
                qty = new BigDecimal(q);
            } catch (NumberFormatException e) {
                qty = BigDecimal.ZERO;
            }
            if (r.ing() == null || qty.signum() <= 0 || q.length() > 8) {
                return ResponseEntity.badRequest().body(Map.of("message", "Заполните выделенные поля: сырьё из справочника и количество."));
            }
            RecipeForm.RecipeItemForm f = new RecipeForm.RecipeItemForm();
            f.setIngredientId(r.ing());
            f.setQuantity(qty.setScale(3, RoundingMode.HALF_UP));
            items.add(f);
        }
        form.setItems(items);
        productService.saveRecipe(productId, form);
        return ResponseEntity.ok(Map.of("ok", true));
    }
}
