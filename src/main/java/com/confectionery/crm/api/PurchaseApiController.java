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
        List<PurchaseService.ItemInput> items = req.items().stream()
                .map(i -> new PurchaseService.ItemInput(i.ingredientId(), i.quantity(), i.totalPrice()))
                .toList();
        return PurchaseDto.from(purchaseService.create(req.counterpartyId(), items, req.purchaseDate(), authentication.getName()));
    }

    /** Фото накладной поставщика — прикладывается отдельным запросом после создания закупки. */
    @PostMapping("/{id}/image")
    public PurchaseDto uploadImage(@PathVariable Long id, @RequestParam("file") MultipartFile file) {
        return PurchaseDto.from(purchaseService.attachImage(id, file));
    }

    /** Повторная ручная выгрузка в 1С — см. PurchaseService.resyncOneC. */
    @PostMapping("/{id}/sync-1c")
    @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT')")
    public PurchaseDto syncOneC(@PathVariable Long id) {
        return PurchaseDto.from(purchaseService.resyncOneC(id));
    }
}
