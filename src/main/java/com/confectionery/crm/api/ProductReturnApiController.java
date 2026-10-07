package com.confectionery.crm.api;

import com.confectionery.crm.api.dto.ProductReturnDto;
import com.confectionery.crm.api.dto.ProductReturnRequest;
import com.confectionery.crm.domain.ProductReturn;
import com.confectionery.crm.service.InvoicePdfService;
import com.confectionery.crm.service.ProductReturnService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

/** Возвраты испорченного / просроченного товара — всем ролям, кроме склада сырья. */
@RestController
@RequestMapping("/api/returns")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT','SALES','WAREHOUSE_FG')")
public class ProductReturnApiController {

    private final ProductReturnService productReturnService;
    private final InvoicePdfService invoicePdfService;

    /**
     * orderId — возвраты, закреплённые за заказом (расчёт в карточке заказа); clientId +
     * unattached=true — ещё не закреплённые возвраты клиента (выбор для закрепления);
     * from/to — список за период; без параметров — все.
     */
    @GetMapping
    public List<ProductReturnDto> list(@RequestParam(required = false) Long orderId,
                                        @RequestParam(required = false) Long clientId,
                                        @RequestParam(required = false, defaultValue = "false") boolean unattached,
                                        @RequestParam(required = false) LocalDate from,
                                        @RequestParam(required = false) LocalDate to) {
        List<ProductReturn> returns;
        if (orderId != null) {
            returns = productReturnService.findByOrder(orderId);
        } else if (clientId != null && unattached) {
            returns = productReturnService.findUnattached(clientId);
        } else if (from != null && to != null) {
            returns = productReturnService.findByDateRange(from, to);
        } else {
            returns = productReturnService.findAll();
        }
        return returns.stream().map(ProductReturnDto::from).toList();
    }

    @GetMapping("/{id}")
    public ProductReturnDto get(@PathVariable Long id) {
        return ProductReturnDto.from(productReturnService.findById(id));
    }

    /**
     * Накладная по возврату в PDF — макет печатной формы 1С, как блок возврата в накладной заказа.
     * Данные — из документа 1С, если она ответила быстро, иначе из CRM (см. OneCInvoiceService).
     */
    @GetMapping("/{id}/invoice")
    public ResponseEntity<byte[]> invoice(@PathVariable Long id) {
        ProductReturn productReturn = productReturnService.findById(id);
        byte[] pdf = invoicePdfService.generateReturn(productReturn);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(InvoicePdfService.returnFileName(productReturn), StandardCharsets.UTF_8)
                        .build().toString())
                .body(pdf);
    }

    @PostMapping
    public ProductReturnDto create(@Valid @RequestBody ProductReturnRequest req, Authentication authentication) {
        return ProductReturnDto.from(productReturnService.create(
                req.clientId(), toItems(req), req.returnDate(), req.reason(), req.orderId(), authentication.getName()));
    }

    private static List<ProductReturnService.ItemInput> toItems(ProductReturnRequest req) {
        return req.items().stream()
                .map(i -> new ProductReturnService.ItemInput(i.productId(), i.quantity(), i.price()))
                .toList();
    }

    @PutMapping("/{id}")
    public ProductReturnDto update(@PathVariable Long id, @Valid @RequestBody ProductReturnRequest req) {
        return ProductReturnDto.from(productReturnService.update(id, req.clientId(), toItems(req), req.returnDate(), req.reason()));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT')")
    public void delete(@PathVariable Long id) {
        productReturnService.delete(id);
    }

    @PutMapping("/{id}/order/{orderId}")
    public ProductReturnDto attach(@PathVariable Long id, @PathVariable Long orderId) {
        return ProductReturnDto.from(productReturnService.attach(id, orderId));
    }

    @DeleteMapping("/{id}/order")
    public ProductReturnDto detach(@PathVariable Long id) {
        return ProductReturnDto.from(productReturnService.detach(id));
    }

    /** Повторная ручная выгрузка в 1С — см. ProductReturnService.resyncOneC. */
    @PostMapping("/{id}/sync-1c")
    @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT')")
    public ProductReturnDto syncOneC(@PathVariable Long id) {
        return ProductReturnDto.from(productReturnService.resyncOneC(id));
    }
}
