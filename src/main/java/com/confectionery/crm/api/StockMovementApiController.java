package com.confectionery.crm.api;

import com.confectionery.crm.api.dto.StockMovementDto;
import com.confectionery.crm.api.dto.StockMovementRequest;
import com.confectionery.crm.domain.StockMovementType;
import com.confectionery.crm.service.IngredientService;
import com.confectionery.crm.service.ProductService;
import com.confectionery.crm.service.StockService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/stock")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('WAREHOUSE_FG','ADMIN','ACCOUNTANT')")
public class StockMovementApiController {

    private final StockService stockService;
    private final IngredientService ingredientService;
    private final ProductService productService;

    @GetMapping("/movements")
    public List<StockMovementDto> movements(@RequestParam(required = false) StockMovementType type) {
        return stockService.findMovements(type).stream().map(StockMovementDto::from).toList();
    }

    @PostMapping("/receipts")
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

    private Long requireIngredientId(StockMovementRequest req) {
        if (req.ingredientId() == null) {
            throw new IllegalArgumentException("Укажите сырьё или товар");
        }
        return req.ingredientId();
    }
}
