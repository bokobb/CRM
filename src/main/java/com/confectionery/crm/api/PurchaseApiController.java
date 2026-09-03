package com.confectionery.crm.api;

import com.confectionery.crm.api.dto.PurchaseDto;
import com.confectionery.crm.api.dto.PurchaseRequest;
import com.confectionery.crm.service.PurchaseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/purchases")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','WAREHOUSE_RAW')")
public class PurchaseApiController {

    private final PurchaseService purchaseService;

    @GetMapping
    public List<PurchaseDto> list() {
        return purchaseService.findAll().stream().map(PurchaseDto::from).toList();
    }

    @PostMapping
    public PurchaseDto create(@Valid @RequestBody PurchaseRequest req, Authentication authentication) {
        return PurchaseDto.from(purchaseService.create(req.ingredientId(), req.quantity(), req.supplier(),
                req.totalPrice(), req.purchaseDate(), authentication.getName()));
    }

    /** Фото накладной поставщика — прикладывается отдельным запросом после создания закупки. */
    @PostMapping("/{id}/image")
    public PurchaseDto uploadImage(@PathVariable Long id, @RequestParam("file") MultipartFile file) {
        return PurchaseDto.from(purchaseService.attachImage(id, file));
    }
}
