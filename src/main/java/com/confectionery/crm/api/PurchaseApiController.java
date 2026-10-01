package com.confectionery.crm.api;

import com.confectionery.crm.api.dto.PurchaseDto;
import com.confectionery.crm.api.dto.PurchaseRequest;
import com.confectionery.crm.service.PurchaseService;
import jakarta.validation.Valid;
import com.confectionery.crm.domain.Purchase;
import com.confectionery.crm.service.InvoicePdfService;
import com.confectionery.crm.service.OneCInvoiceService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
    private final InvoicePdfService invoicePdfService;

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

    /** Правка закупки — см. PurchaseService.update. */
    @PutMapping("/{id}")
    public PurchaseDto update(@PathVariable Long id, @Valid @RequestBody PurchaseRequest req, Authentication authentication) {
        List<PurchaseService.ItemInput> items = req.items().stream()
                .map(i -> new PurchaseService.ItemInput(i.ingredientId(), i.quantity(), i.totalPrice()))
                .toList();
        return PurchaseDto.from(purchaseService.update(id, req.counterpartyId(), items, req.purchaseDate(), authentication.getName()));
    }

    /** Фото накладной поставщика — прикладывается отдельным запросом после создания закупки. */
    @PostMapping("/{id}/image")
    public PurchaseDto uploadImage(@PathVariable Long id, @RequestParam("file") MultipartFile file) {
        return PurchaseDto.from(purchaseService.attachImage(id, file));
    }

    /**
     * Приходная накладная по закупке в PDF — по макету печатной формы 1С, с данными документа
     * "Поступление" из 1С (см. InvoicePdfService.generatePurchase). Приложение показывает её в
     * предпросмотре по свайпу карточки закупки и делится ею по кнопке "Поделиться".
     */
    @GetMapping("/{id}/invoice")
    public ResponseEntity<byte[]> invoice(@PathVariable Long id) {
        Purchase purchase = purchaseService.findById(id);
        byte[] pdf = invoicePdfService.generatePurchase(purchase);
        String filename = "prihodnaya_" + OneCInvoiceService.purchaseInvoiceNumber(purchase) + ".pdf";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(pdf);
    }

    /** Повторная ручная выгрузка в 1С — см. PurchaseService.resyncOneC. */
    @PostMapping("/{id}/sync-1c")
    @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT')")
    public PurchaseDto syncOneC(@PathVariable Long id) {
        return PurchaseDto.from(purchaseService.resyncOneC(id));
    }
}
