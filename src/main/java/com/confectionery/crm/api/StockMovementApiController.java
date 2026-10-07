package com.confectionery.crm.api;

import com.confectionery.crm.api.dto.OneCSyncResultDto;
import com.confectionery.crm.api.dto.StockMovementDto;
import com.confectionery.crm.api.dto.StockMovementRequest;
import com.confectionery.crm.domain.StockMovementType;
import com.confectionery.crm.service.IngredientService;
import com.confectionery.crm.service.OneCStockSyncService;
import com.confectionery.crm.service.ProductService;
import com.confectionery.crm.service.StockService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * Только WAREHOUSE_FG/ADMIN/ACCOUNTANT видят приход (см. {@link #receiveBatch}) — сырьё
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

    /**
     * Документ формы «Поступление»/«Списание»: несколько позиций одного вида (только товар или
     * только сырьё) с датой; у списания причина обязательна. Уходит в 1С проведённым документом
     * в фоне — см. StockService.createDocument.
     */
    public record StockDocumentRequest(LocalDate date, String reason, List<StockMovementRequest> items) {
    }

    @PostMapping("/receipts/batch")
    @PreAuthorize("hasAnyRole('WAREHOUSE_FG','ADMIN','ACCOUNTANT')")
    public List<StockMovementDto> receiveBatch(@RequestBody StockDocumentRequest req, Authentication authentication) {
        return createDocument(StockMovementType.RECEIPT, req, authentication);
    }

    @PostMapping("/writeoffs/batch")
    public List<StockMovementDto> writeOffBatch(@RequestBody StockDocumentRequest req, Authentication authentication) {
        return createDocument(StockMovementType.WRITE_OFF, req, authentication);
    }

    private List<StockMovementDto> createDocument(StockMovementType type, StockDocumentRequest req, Authentication authentication) {
        List<StockMovementRequest> items = req.items() == null ? List.of() : req.items();
        List<StockService.DocumentLine> lines = items.stream()
                .map(i -> {
                    if (i.quantity() == null) {
                        throw new IllegalArgumentException("Укажите количество");
                    }
                    return i.productId() != null
                            ? new StockService.DocumentLine(null, productService.findById(i.productId()), i.quantity())
                            : new StockService.DocumentLine(ingredientService.findById(requireIngredientId(i)), null, i.quantity());
                })
                .toList();
        boolean isProduct = !items.isEmpty() && items.get(0).productId() != null;
        return stockService.createDocument(type, isProduct, req.date(), req.reason(), lines, authentication.getName())
                .getMovements().stream().map(StockMovementDto::from).toList();
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

    private Long requireIngredientId(StockMovementRequest req) {
        if (req.ingredientId() == null) {
            throw new IllegalArgumentException("Укажите сырьё или товар");
        }
        return req.ingredientId();
    }
}
