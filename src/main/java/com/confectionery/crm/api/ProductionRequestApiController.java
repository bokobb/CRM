package com.confectionery.crm.api;

import com.confectionery.crm.api.dto.*;
import com.confectionery.crm.service.ProductionRequestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/production-requests")
@RequiredArgsConstructor
public class ProductionRequestApiController {

    private final ProductionRequestService requestService;

    @GetMapping
    public List<ProductionRequestSummaryDto> list() {
        return requestService.findAll().stream().map(ProductionRequestSummaryDto::from).toList();
    }

    @GetMapping("/{id}")
    public ProductionRequestDto get(@PathVariable Long id) {
        return ProductionRequestDto.from(requestService.findById(id));
    }

    /** Товары, у которых есть рецептура — для выбора при создании заявки. */
    @GetMapping("/eligible-products")
    public List<ProductDto> eligibleProducts() {
        return requestService.productsWithRecipe().stream().map(ProductDto::from).toList();
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT')")
    public ProductionRequestDto create(@Valid @RequestBody ProductionRequestCreateRequest req, Authentication authentication) {
        return ProductionRequestDto.from(requestService.create(req.productId(), req.batches(), authentication.getName()));
    }

    @PostMapping("/{id}/collect")
    @PreAuthorize("hasAnyRole('ADMIN','WAREHOUSE_RAW')")
    public ProductionRequestDto collect(@PathVariable Long id, @Valid @RequestBody ProductionRequestCollectRequest req,
                                         Authentication authentication) {
        return ProductionRequestDto.from(requestService.collect(id, Set.copyOf(req.itemIds()), authentication.getName()));
    }

    @PostMapping("/{id}/receive")
    @PreAuthorize("hasAnyRole('ADMIN','WAREHOUSE_FG')")
    public ProductionRequestDto receive(@PathVariable Long id, @Valid @RequestBody ProductionRequestReceiveRequest req,
                                         Authentication authentication) {
        return ProductionRequestDto.from(requestService.receive(id, req.quantity(), authentication.getName()));
    }
}
