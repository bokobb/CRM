package com.confectionery.crm.web;

import com.confectionery.crm.domain.Ingredient;
import com.confectionery.crm.domain.Product;
import com.confectionery.crm.domain.StockMovementType;
import com.confectionery.crm.repository.StockMovementRepository;
import com.confectionery.crm.service.IngredientService;
import com.confectionery.crm.service.OneCReceiptSyncService;
import com.confectionery.crm.service.ProductService;
import com.confectionery.crm.service.StockService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.util.List;
import java.time.LocalDate;

/**
 * Поступления и списания готовой продукции ("Склад", см. WarehouseStockController) и сырья
 * ("Склад сырья", см. IngredientController) — журнал StockMovement, веб-аналог мобильного
 * экрана StockMovementScreen (см. mobile/lib/screens/stock_movement_screen.dart), поверх уже
 * готового StockService, которым и мобильное приложение пользуется через REST (см.
 * StockMovementApiController). "Остатки" — отдельно (WarehouseStockController/IngredientController).
 * Показываются только РУЧНЫЕ движения (см. StockMovement.manual) — то, что списывается/
 * приходуется автоматически заявками на производство, здесь намеренно не отображается.
 *
 * <p>Товар и сырьё — две независимые пары страниц ("Склад" и "Склад сырья" в меню), каждая
 * видит только свой вид номенклатуры — раньше это была одна пара страниц с переключателем
 * "Вид" внутри формы, из-за чего приход/списание сырья и товара было не различить в общем
 * меню; теперь структура зеркальна "Остаткам", где такое разделение уже было.
 */
@Controller
@RequiredArgsConstructor
public class WarehouseMovementController {

    private static final String PRODUCT = "PRODUCT";
    private static final String INGREDIENT = "INGREDIENT";

    private final StockService stockService;
    private final ProductService productService;
    private final IngredientService ingredientService;
    private final OneCReceiptSyncService oneCReceiptSyncService;
    private final StockMovementRepository stockMovementRepository;

    @GetMapping("/warehouse/receipts")
    public String productReceipts(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
                                   @RequestParam(required = false) String q, Model model) {
        return page(model, StockMovementType.RECEIPT, PRODUCT, "warehouse-receipts", from, to, q);
    }

    @GetMapping("/warehouse/writeoffs")
    public String productWriteoffs(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
                                    @RequestParam(required = false) String q, Model model) {
        return page(model, StockMovementType.WRITE_OFF, PRODUCT, "warehouse-writeoffs", from, to, q);
    }

    @GetMapping("/ingredients/receipts")
    public String ingredientReceipts(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
                                      @RequestParam(required = false) String q, Model model) {
        return page(model, StockMovementType.RECEIPT, INGREDIENT, "ingredients-receipts", from, to, q);
    }

    @GetMapping("/ingredients/writeoffs")
    public String ingredientWriteoffs(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
                                       @RequestParam(required = false) String q, Model model) {
        return page(model, StockMovementType.WRITE_OFF, INGREDIENT, "ingredients-writeoffs", from, to, q);
    }

    private String page(Model model, StockMovementType type, String kind, String activePage, LocalDate from, LocalDate to, String q) {
        model.addAttribute("activePage", activePage);
        model.addAttribute("isReceipt", type == StockMovementType.RECEIPT);
        model.addAttribute("kind", kind);
        model.addAttribute("receiptsUrl", kind.equals(PRODUCT) ? "/warehouse/receipts" : "/ingredients/receipts");
        model.addAttribute("writeoffsUrl", kind.equals(PRODUCT) ? "/warehouse/writeoffs" : "/ingredients/writeoffs");
        var movements = stockService.findMovements(type,
                from == null ? null : from.atStartOfDay(),
                to == null ? null : to.plusDays(1).atStartOfDay(),
                q).stream()
                .filter(m -> kind.equals(PRODUCT) ? m.getProduct() != null : m.getIngredient() != null)
                .toList();
        model.addAttribute("movements", movements);
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        model.addAttribute("q", q);
        if (kind.equals(PRODUCT)) {
            model.addAttribute("products", productService.findAll());
        } else {
            model.addAttribute("ingredients", ingredientService.findAllActive());
        }
        return "warehouse/movements";
    }

    @PostMapping("/warehouse/receipts")
    @PreAuthorize("hasAnyRole('WAREHOUSE_FG','ADMIN','ACCOUNTANT')")
    public String createProductReceipt(@RequestParam Long productId, @RequestParam BigDecimal quantity,
                                        @RequestParam(required = false) String reason,
                                        Authentication authentication, RedirectAttributes redirectAttributes) {
        submit(StockMovementType.RECEIPT, PRODUCT, productId, null, quantity, reason, authentication, redirectAttributes);
        return "redirect:/warehouse/receipts";
    }

    /**
     * "Общее оприходование" — собирает все ещё не выгруженные в 1С РУЧНЫЕ поступления товара
     * за день в один документ "Оприходование товаров" и сразу проводит его в 1С на 06:00 (см.
     * OneCReceiptSyncService). Приход от заявок на производство сюда не относится — тот
     * выгружается сам, отдельным документом на каждую заявку.
     */
    @PostMapping("/warehouse/receipts/post-1c")
    @PreAuthorize("hasAnyRole('ADMIN','WAREHOUSE_FG')")
    public String postReceiptsOneC(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                    RedirectAttributes redirectAttributes) {
        LocalDate d = date != null ? date : LocalDate.now();
        try {
            var result = oneCReceiptSyncService.postDailyReceipts(d);
            String message = result.documentNumber() == null
                    ? "Оприходовывать нечего — все сегодняшние ручные поступления уже выгружены в 1С"
                    : "Оприходовано в 1С: позиций " + result.movementsCount() + ", документ №" + result.documentNumber();
            if (!result.unresolved().isEmpty()) {
                message += ". Не сопоставлено с 1С: " + String.join(", ", result.unresolved());
            }
            redirectAttributes.addFlashAttribute("success", message);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Не удалось оприходовать в 1С: " + e.getMessage());
        }
        return "redirect:/warehouse/receipts";
    }

    @PostMapping("/warehouse/writeoffs")
    @PreAuthorize("hasAnyRole('WAREHOUSE_FG','WAREHOUSE_RAW','ADMIN','ACCOUNTANT')")
    public String createProductWriteOff(@RequestParam Long productId, @RequestParam BigDecimal quantity,
                                         @RequestParam(required = false) String reason,
                                         Authentication authentication, RedirectAttributes redirectAttributes) {
        submit(StockMovementType.WRITE_OFF, PRODUCT, productId, null, quantity, reason, authentication, redirectAttributes);
        return "redirect:/warehouse/writeoffs";
    }

    @PostMapping("/ingredients/receipts")
    @PreAuthorize("hasAnyRole('WAREHOUSE_FG','ADMIN','ACCOUNTANT')")
    public String createIngredientReceipt(@RequestParam Long ingredientId, @RequestParam BigDecimal quantity,
                                           @RequestParam(required = false) String reason,
                                           Authentication authentication, RedirectAttributes redirectAttributes) {
        submit(StockMovementType.RECEIPT, INGREDIENT, null, ingredientId, quantity, reason, authentication, redirectAttributes);
        return "redirect:/ingredients/receipts";
    }

    @PostMapping("/ingredients/writeoffs")
    @PreAuthorize("hasAnyRole('WAREHOUSE_FG','WAREHOUSE_RAW','ADMIN','ACCOUNTANT')")
    public String createIngredientWriteOff(@RequestParam Long ingredientId, @RequestParam BigDecimal quantity,
                                            @RequestParam(required = false) String reason,
                                            Authentication authentication, RedirectAttributes redirectAttributes) {
        submit(StockMovementType.WRITE_OFF, INGREDIENT, null, ingredientId, quantity, reason, authentication, redirectAttributes);
        return "redirect:/ingredients/writeoffs";
    }

    /**
     * Удаление ошибочно созданного ручного поступления/списания — откатывает остаток (см.
     * StockService.deleteMovement). Редиректим обратно на ту же страницу, откуда удаляли (вид
     * номенклатуры и тип движения смотрим до удаления, т.к. после него сущности уже нет).
     */
    @PostMapping("/warehouse/movements/{id}/delete")
    @PreAuthorize("hasAnyRole('WAREHOUSE_FG','WAREHOUSE_RAW','ADMIN','ACCOUNTANT')")
    public String deleteMovement(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        String redirectTo = stockMovementRepository.findById(id)
                .map(m -> {
                    String base = m.getProduct() != null ? "/warehouse" : "/ingredients";
                    return base + (m.getType() == StockMovementType.RECEIPT ? "/receipts" : "/writeoffs");
                })
                .orElse("/warehouse/receipts");
        try {
            stockService.deleteMovement(id);
            redirectAttributes.addFlashAttribute("success", "Движение удалено, остаток пересчитан");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Не удалось удалить: " + e.getMessage());
        }
        return "redirect:" + redirectTo;
    }

    /** Одна позиция с веба — тот же документ формы, что и в приложении (уходит в 1С, см. StockService.createDocument). */
    private void submit(StockMovementType type, String kind, Long productId, Long ingredientId, BigDecimal quantity,
                         String reason, Authentication authentication, RedirectAttributes redirectAttributes) {
        try {
            String by = authentication.getName();
            StockService.DocumentLine line;
            if (PRODUCT.equals(kind)) {
                if (productId == null) {
                    throw new IllegalArgumentException("Выберите товар");
                }
                line = new StockService.DocumentLine(null, productService.findById(productId), quantity);
            } else {
                if (ingredientId == null) {
                    throw new IllegalArgumentException("Выберите сырьё");
                }
                line = new StockService.DocumentLine(ingredientService.findById(ingredientId), null, quantity);
            }
            stockService.createDocument(type, PRODUCT.equals(kind), null, reason, List.of(line), by);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
    }
}
