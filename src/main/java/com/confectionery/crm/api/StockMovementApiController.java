package com.confectionery.crm.api;

import com.confectionery.crm.api.dto.OneCReceiptResultDto;
import com.confectionery.crm.api.dto.OneCSyncResultDto;
import com.confectionery.crm.api.dto.StockMovementDto;
import com.confectionery.crm.api.dto.StockMovementRequest;
import com.confectionery.crm.domain.StockMovementType;
import com.confectionery.crm.service.IngredientService;
import com.confectionery.crm.service.OneCReceiptSyncService;
import com.confectionery.crm.service.OneCStockSyncService;
import com.confectionery.crm.service.ProductService;
import com.confectionery.crm.service.StockService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * Только WAREHOUSE_FG/ADMIN/ACCOUNTANT видят приход товара (см. {@link #receive}) — сырьё
 * обычно приходует Покупками (см. PurchaseService), не этой ручной формой. Остальные методы
 * (список движений, списание, удаление) открыты и WAREHOUSE_RAW — тому же в мобильном
 * приложении нужен раздел "Склад сырья" (Остатки/Поступления/Списание, см. IngredientWarehouseScreen
 * в мобильном приложении и IngredientController/WarehouseMovementController на вебе).
 */
@RestController
@RequestMapping("/api/stock")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('WAREHOUSE_FG','WAREHOUSE_RAW','ADMIN','ACCOUNTANT')")
public class StockMovementApiController {

    private final StockService stockService;
    private final IngredientService ingredientService;
    private final ProductService productService;
    private final OneCStockSyncService oneCStockSyncService;
    private final OneCReceiptSyncService oneCReceiptSyncService;

    /** Только ручные движения — см. StockMovement.manual/StockService.findMovements. */
    @GetMapping("/movements")
    public List<StockMovementDto> movements(@RequestParam(required = false) StockMovementType type,
                                             @RequestParam(required = false) LocalDate from,
                                             @RequestParam(required = false) LocalDate to,
                                             @RequestParam(required = false) String q) {
        return stockService.findMovements(type,
                from == null ? null : from.atStartOfDay(),
                to == null ? null : to.plusDays(1).atStartOfDay(),
                q).stream().map(StockMovementDto::from).toList();
    }

    @PostMapping("/receipts")
    @PreAuthorize("hasAnyRole('WAREHOUSE_FG','ADMIN','ACCOUNTANT')")
    public StockMovementDto receive(@Valid @RequestBody StockMovementRequest req, Authentication authentication) {
        if (req.productId() != null) {
            var product = productService.findById(req.productId());
            return StockMovementDto.from(stockService.receiveProduct(product, req.quantity(), req.reason(), authentication.getName()));
        }
        var ingredient = ingredientService.findById(requireIngredientId(req));
        return StockMovementDto.from(stockService.receive(ingredient, req.quantity(), req.reason(), authentication.getName()));
    }

    @PostMapping("/writeoffs")
    public StockMovementDto writeOff(@Valid @RequestBody StockMovementRequest req, Authentication authentication) {
        if (req.productId() != null) {
            var product = productService.findById(req.productId());
            return StockMovementDto.from(stockService.writeOffProduct(product, req.quantity(), req.reason(), authentication.getName()));
        }
        var ingredient = ingredientService.findById(requireIngredientId(req));
        return StockMovementDto.from(stockService.writeOff(ingredient, req.quantity(), req.reason(), authentication.getName()));
    }

    /** Удаление ошибочно созданного ручного поступления/списания — откатывает остаток (см. StockService.deleteMovement). */
    @DeleteMapping("/movements/{id}")
    public void deleteMovement(@PathVariable Long id) {
        stockService.deleteMovement(id);
    }

    /** Ручной запуск синхронизации остатков с 1С (обычно она идёт по расписанию) — только ADMIN. */
    @PostMapping("/sync-1c")
    @PreAuthorize("hasRole('ADMIN')")
    public OneCSyncResultDto syncOneC() {
        return OneCSyncResultDto.from(oneCStockSyncService.sync());
    }

    /**
     * "Общее оприходование" — собирает все ещё не выгруженные в 1С РУЧНЫЕ поступления товара
     * за день в один документ "Оприходование товаров" и сразу проводит его в 1С на 06:00 (см.
     * OneCReceiptSyncService). Приход от заявок на производство сюда не относится.
     */
    @PostMapping("/receipts/post-1c")
    @PreAuthorize("hasAnyRole('ADMIN','WAREHOUSE_FG')")
    public OneCReceiptResultDto postReceiptsOneC(@RequestParam(required = false) LocalDate date) {
        LocalDate d = date != null ? date : LocalDate.now();
        return OneCReceiptResultDto.from(oneCReceiptSyncService.postDailyReceipts(d));
    }

    private Long requireIngredientId(StockMovementRequest req) {
        if (req.ingredientId() == null) {
            throw new IllegalArgumentException("Укажите сырьё или товар");
        }
        return req.ingredientId();
    }
}
