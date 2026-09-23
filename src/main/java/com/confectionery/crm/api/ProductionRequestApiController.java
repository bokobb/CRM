package com.confectionery.crm.api;

import com.confectionery.crm.api.dto.*;
import com.confectionery.crm.domain.ProductionRequest;
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
        return toDto(requestService.findById(id));
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
                .map(this::toDto).toList();
    }

    /** Редактирование заявки — позиции и дата документа; название пересчитывается по группам товаров. */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT')")
    public ProductionRequestDto update(@PathVariable Long id, @Valid @RequestBody ProductionRequestCreateRequest req,
                                        Authentication authentication) {
        List<ProductionRequestService.Line> lines = req.items().stream()
                .map(i -> new ProductionRequestService.Line(i.productId(), i.batches()))
                .toList();
        return toDto(requestService.update(id, lines, authentication.getName(), req.documentDate()));
    }

    /**
     * Отмена заявки в ЛЮБОМ статусе, кроме уже отменённой — см. ProductionRequestService.cancel:
     * откатывает складские остатки (если были списаны/оприходованы) и распроводит документы в
     * 1С (остаются черновиками). Удаление с пометкой документов 1С — отдельно, DELETE ниже.
     * Только ADMIN, т.к. затрагивает реальные остатки склада.
     */
    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('ADMIN')")
    public ProductionRequestDto cancel(@PathVariable Long id, Authentication authentication) {
        return toDto(requestService.cancel(id, authentication.getName()));
    }

    /** Удаление отменённой заявки — документы в 1С помечаются на удаление (см. ProductionRequestService.delete). */
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
        return toDto(
                requestService.substituteItem(id, itemId, req.ingredientId(), req.quantity(), authentication.getName()));
    }

    /** Добавление совсем новой позиции сырья, которой не было в заявке (см. ProductionRequestService.addItem). */
    @PostMapping("/{id}/items/add")
    @PreAuthorize("hasAnyRole('ADMIN','WAREHOUSE_RAW')")
    public ProductionRequestDto addItem(@PathVariable Long id, @Valid @RequestBody ProductionRequestAddItemRequest req,
                                         Authentication authentication) {
        return toDto(
                requestService.addItem(id, req.ingredientId(), req.quantity(), authentication.getName()));
    }

    /** Удаление позиции сырья из заявки (см. ProductionRequestService.removeItem). */
    @DeleteMapping("/{id}/items/{itemId}")
    @PreAuthorize("hasAnyRole('ADMIN','WAREHOUSE_RAW')")
    public ProductionRequestDto removeItem(@PathVariable Long id, @PathVariable Long itemId, Authentication authentication) {
        return toDto(requestService.removeItem(id, itemId, authentication.getName()));
    }

    @PostMapping("/{id}/collect")
    @PreAuthorize("hasAnyRole('ADMIN','WAREHOUSE_RAW')")
    public ProductionRequestDto collect(@PathVariable Long id, @Valid @RequestBody ProductionRequestCollectRequest req,
                                         Authentication authentication) {
        return toDto(requestService.collect(id, Set.copyOf(req.itemIds()), authentication.getName()));
    }

    /** Оприходовать ОДНУ строку заявки — у каждого товара в заявке свой фактический выход. */
    @PostMapping("/{id}/lines/{lineId}/receive")
    @PreAuthorize("hasAnyRole('ADMIN','WAREHOUSE_FG')")
    public ProductionRequestDto receiveLine(@PathVariable Long id, @PathVariable Long lineId,
                                             @Valid @RequestBody ProductionRequestReceiveRequest req,
                                             Authentication authentication) {
        return toDto(requestService.receiveLine(id, lineId, req.quantity(), authentication.getName()));
    }

    /** Повторная ручная выгрузка в 1С — см. ProductionRequestService.resyncOneC. */
    @PostMapping("/{id}/sync-1c")
    @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT')")
    public ProductionRequestDto syncOneC(@PathVariable Long id) {
        return toDto(requestService.resyncOneC(id));
    }

    /**
     * "Завершить" — вручную проводит требование-накладную (списание сырья) в 1С, когда состав
     * заявки точно больше не изменится (см. ProductionRequestService.completeRequisition).
     * Отчёт производства эта кнопка не трогает — он проводится сам при полном оприходовании.
     * WAREHOUSE_RAW — не ACCOUNTANT: именно зав. склада сырья видит, что после готовки осталось
     * лишнее сырьё, корректирует состав (см. substituteItem) и завершает заявку сам.
     */
    @PostMapping("/{id}/complete-1c")
    @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT','WAREHOUSE_RAW')")
    public ProductionRequestDto completeOneC(@PathVariable Long id) {
        return toDto(requestService.completeRequisition(id));
    }

    /**
     * "Отменить" (после "Завершить") — распроводит требование-накладную в 1С и снова разрешает
     * править состав сырья (см. ProductionRequestService.cancelCompletion).
     */
    @PostMapping("/{id}/cancel-completion")
    @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT','WAREHOUSE_RAW')")
    public ProductionRequestDto cancelCompletion(@PathVariable Long id) {
        return toDto(requestService.cancelCompletion(id));
    }

    private ProductionRequestDto toDto(ProductionRequest r) {
        return ProductionRequestDto.from(r, requestService.massSummary(r));
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
