package com.confectionery.crm.api;

import com.confectionery.crm.api.dto.*;
import com.confectionery.crm.service.ProductionReportPdfService;
import com.confectionery.crm.service.ProductionRequestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/production-requests")
@RequiredArgsConstructor
public class ProductionRequestApiController {

    private final ProductionRequestService requestService;
    private final ProductionReportPdfService reportPdfService;

    @GetMapping
    public List<ProductionRequestSummaryDto> list() {
        return requestService.findAll().stream().map(ProductionRequestSummaryDto::from).toList();
    }

    @GetMapping("/{id}")
    public ProductionRequestDto get(@PathVariable Long id) {
        return ProductionRequestDto.from(requestService.findById(id));
    }

    /** Товары, у которых есть рецептура — для выбора (с поиском) при создании заявки. */
    @GetMapping("/eligible-products")
    public List<ProductionEligibleProductDto> eligibleProducts() {
        return requestService.recipesWithProducts().stream().map(ProductionEligibleProductDto::from).toList();
    }

    /**
     * Позиции автоматически разбиваются по группе товара (Product.group) — на каждую группу
     * создаётся своя заявка с названием = названием группы (см. ProductionRequestService.createGrouped).
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT')")
    public List<ProductionRequestDto> create(@Valid @RequestBody ProductionRequestCreateRequest req, Authentication authentication) {
        List<ProductionRequestService.Line> lines = req.items().stream()
                .map(i -> new ProductionRequestService.Line(i.productId(), i.batches()))
                .toList();
        return requestService.createGrouped(lines, authentication.getName(), req.documentDate()).stream()
                .map(ProductionRequestDto::from).toList();
    }

    /** Редактирование ещё не собранной заявки — только позиции и дата документа, название заявки не меняется. */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT')")
    public ProductionRequestDto update(@PathVariable Long id, @Valid @RequestBody ProductionRequestCreateRequest req,
                                        Authentication authentication) {
        List<ProductionRequestService.Line> lines = req.items().stream()
                .map(i -> new ProductionRequestService.Line(i.productId(), i.batches()))
                .toList();
        return ProductionRequestDto.from(requestService.update(id, lines, authentication.getName(), req.documentDate()));
    }

    /** Отмена ещё не собранной заявки. */
    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT')")
    public ProductionRequestDto cancel(@PathVariable Long id, Authentication authentication) {
        return ProductionRequestDto.from(requestService.cancel(id, authentication.getName()));
    }

    /**
     * Полное удаление заявки в ЛЮБОМ статусе (в отличие от cancel — только для «Новой») — см.
     * ProductionRequestService.delete. Только ADMIN — необратимо, откатывает складские остатки.
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable Long id) {
        requestService.delete(id);
    }

    /** Замена сырья в позиции — например, нужного не хватило на складе (см. ProductionRequestService.substituteItem). */
    @PostMapping("/{id}/items/{itemId}/substitute")
    @PreAuthorize("hasAnyRole('ADMIN','WAREHOUSE_RAW')")
    public ProductionRequestDto substituteItem(@PathVariable Long id, @PathVariable Long itemId,
                                                @Valid @RequestBody ProductionRequestSubstituteRequest req,
                                                Authentication authentication) {
        return ProductionRequestDto.from(
                requestService.substituteItem(id, itemId, req.ingredientId(), req.quantity(), authentication.getName()));
    }

    @PostMapping("/{id}/collect")
    @PreAuthorize("hasAnyRole('ADMIN','WAREHOUSE_RAW')")
    public ProductionRequestDto collect(@PathVariable Long id, @Valid @RequestBody ProductionRequestCollectRequest req,
                                         Authentication authentication) {
        return ProductionRequestDto.from(requestService.collect(id, Set.copyOf(req.itemIds()), authentication.getName()));
    }

    /** Оприходовать ОДНУ строку заявки — у каждого товара в заявке свой фактический выход. */
    @PostMapping("/{id}/lines/{lineId}/receive")
    @PreAuthorize("hasAnyRole('ADMIN','WAREHOUSE_FG')")
    public ProductionRequestDto receiveLine(@PathVariable Long id, @PathVariable Long lineId,
                                             @Valid @RequestBody ProductionRequestReceiveRequest req,
                                             Authentication authentication) {
        return ProductionRequestDto.from(requestService.receiveLine(id, lineId, req.quantity(), authentication.getName()));
    }

    /** Повторная ручная выгрузка в 1С — см. ProductionRequestService.resyncOneC. */
    @PostMapping("/{id}/sync-1c")
    @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT')")
    public ProductionRequestDto syncOneC(@PathVariable Long id) {
        return ProductionRequestDto.from(requestService.resyncOneC(id));
    }

    /** Дневной отчёт "сколько сегодня изготовили" — date не задан = сегодня. */
    @GetMapping("/report")
    public DailyProductionReportDto report(@RequestParam(required = false) LocalDate date) {
        LocalDate d = date != null ? date : LocalDate.now();
        return DailyProductionReportDto.of(d, requestService.dailyReport(d));
    }

    @GetMapping("/report/pdf")
    public ResponseEntity<byte[]> reportPdf(@RequestParam(required = false) LocalDate date) {
        LocalDate d = date != null ? date : LocalDate.now();
        byte[] pdf = reportPdfService.generate(d, d, requestService.dailyReport(d));
        String filename = "otchet_" + DateTimeFormatter.ISO_LOCAL_DATE.format(d) + ".pdf";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + filename)
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }
}
