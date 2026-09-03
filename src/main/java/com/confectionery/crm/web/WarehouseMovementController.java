package com.confectionery.crm.web;

import com.confectionery.crm.domain.Ingredient;
import com.confectionery.crm.domain.Product;
import com.confectionery.crm.domain.StockMovementType;
import com.confectionery.crm.service.IngredientService;
import com.confectionery.crm.service.ProductService;
import com.confectionery.crm.service.StockService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Поступления и списания сырья/готовой продукции (журнал StockMovement) — веб-аналог
 * мобильного экрана StockMovementScreen (см. mobile/lib/screens/stock_movement_screen.dart),
 * поверх уже готового StockService, которым и мобильное приложение пользуется через REST
 * (см. StockMovementApiController). "Остатки" — отдельно, см. WarehouseStockController.
 * Показываются только РУЧНЫЕ движения (см. StockMovement.manual) — то, что списывается/
 * приходуется автоматически заявками на производство, здесь намеренно не отображается.
 */
@Controller
@RequiredArgsConstructor
public class WarehouseMovementController {

    private final StockService stockService;
    private final ProductService productService;
    private final IngredientService ingredientService;

    @GetMapping("/warehouse/receipts")
    public String receipts(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
                            @RequestParam(required = false) String q, Model model) {
        return page(model, StockMovementType.RECEIPT, "warehouse-receipts", from, to, q);
    }

    @GetMapping("/warehouse/writeoffs")
    public String writeoffs(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
                             @RequestParam(required = false) String q, Model model) {
        return page(model, StockMovementType.WRITE_OFF, "warehouse-writeoffs", from, to, q);
    }

    private String page(Model model, StockMovementType type, String activePage, LocalDate from, LocalDate to, String q) {
        model.addAttribute("activePage", activePage);
        model.addAttribute("isReceipt", type == StockMovementType.RECEIPT);
        model.addAttribute("movements", stockService.findMovements(type,
                from == null ? null : from.atStartOfDay(),
                to == null ? null : to.plusDays(1).atStartOfDay(),
                q));
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        model.addAttribute("q", q);
        model.addAttribute("products", productService.findAll());
        model.addAttribute("ingredients", ingredientService.findAll());
        return "warehouse/movements";
    }

    @PostMapping("/warehouse/receipts")
    @PreAuthorize("hasAnyRole('WAREHOUSE_FG','ADMIN','ACCOUNTANT')")
    public String createReceipt(@RequestParam String kind,
                                 @RequestParam(required = false) Long productId,
                                 @RequestParam(required = false) Long ingredientId,
                                 @RequestParam BigDecimal quantity,
                                 @RequestParam(required = false) String reason,
                                 Authentication authentication,
                                 RedirectAttributes redirectAttributes) {
        submit(StockMovementType.RECEIPT, kind, productId, ingredientId, quantity, reason, authentication, redirectAttributes);
        return "redirect:/warehouse/receipts";
    }

    @PostMapping("/warehouse/writeoffs")
    @PreAuthorize("hasAnyRole('WAREHOUSE_FG','WAREHOUSE_RAW','ADMIN','ACCOUNTANT')")
    public String createWriteOff(@RequestParam String kind,
                                  @RequestParam(required = false) Long productId,
                                  @RequestParam(required = false) Long ingredientId,
                                  @RequestParam BigDecimal quantity,
                                  @RequestParam(required = false) String reason,
                                  Authentication authentication,
                                  RedirectAttributes redirectAttributes) {
        submit(StockMovementType.WRITE_OFF, kind, productId, ingredientId, quantity, reason, authentication, redirectAttributes);
        return "redirect:/warehouse/writeoffs";
    }

    private void submit(StockMovementType type, String kind, Long productId, Long ingredientId, BigDecimal quantity,
                         String reason, Authentication authentication, RedirectAttributes redirectAttributes) {
        try {
            String by = authentication.getName();
            if ("PRODUCT".equals(kind)) {
                if (productId == null) {
                    throw new IllegalArgumentException("Выберите товар");
                }
                Product product = productService.findById(productId);
                if (type == StockMovementType.RECEIPT) {
                    stockService.receiveProduct(product, quantity, reason, by);
                } else {
                    stockService.writeOffProduct(product, quantity, reason, by);
                }
            } else {
                if (ingredientId == null) {
                    throw new IllegalArgumentException("Выберите сырьё");
                }
                Ingredient ingredient = ingredientService.findById(ingredientId);
                if (type == StockMovementType.RECEIPT) {
                    stockService.receive(ingredient, quantity, reason, by);
                } else {
                    stockService.writeOff(ingredient, quantity, reason, by);
                }
            }
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
    }
}
