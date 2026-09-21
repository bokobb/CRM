package com.confectionery.crm.web;

import com.confectionery.crm.api.dto.ProductionRequestSummaryDto;
import com.confectionery.crm.domain.ProductionRequest;
import com.confectionery.crm.domain.ProductionRequestLine;
import com.confectionery.crm.domain.ProductionRequestStatus;
import com.confectionery.crm.service.IngredientService;
import com.confectionery.crm.service.ProductionReportPdfService;
import com.confectionery.crm.service.ProductionRequestService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Заявки на сырьё под производство: бухгалтер создаёт (сразу под несколько товаров) ->
 * зав. склада сырья собирает по общему чек-листу (списывает сырьё) -> зав. склада ГП
 * оприходует фактический выход по каждому товару отдельно. См. ProductionRequestService —
 * вся бизнес-логика и переиспользование StockService там.
 */
@Controller
@RequestMapping("/production-requests")
@RequiredArgsConstructor
public class ProductionRequestController {

    private final ProductionRequestService requestService;
    private final ProductionReportPdfService reportPdfService;
    private final IngredientService ingredientService;

    /**
     * По умолчанию показываются заявки с датой документа = сегодня — {@code all=true} снимает
     * фильтр по дате. Фильтруем и показываем именно {@code documentDate} (дата документа для
     * 1С, которую бухгалтер выбирает сам при создании), а не {@code createdAt} (дату/время
     * создания записи в CRM) — иначе список не совпадает с тем, что видно в 1С, если заявка
     * создана "на завтра"/"задним числом".
     */
    @GetMapping
    public String list(@RequestParam(required = false) String status,
                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                        @RequestParam(defaultValue = "false") boolean all,
                        Model model) {
        model.addAttribute("activePage", "production-requests");
        LocalDate filterDate = all ? null : (date != null ? date : LocalDate.now());
        List<ProductionRequestSummaryDto> requests = requestService.findAll().stream()
                .map(ProductionRequestSummaryDto::from)
                .filter(r -> status == null || status.equals(r.status()))
                .filter(r -> filterDate == null || filterDate.equals(r.documentDate()))
                .toList();
        model.addAttribute("requests", requests);
        model.addAttribute("selectedStatus", status);
        model.addAttribute("statuses", ProductionRequestStatus.values());
        model.addAttribute("date", filterDate);
        model.addAttribute("all", all);
        return "production-requests/list";
    }

    @GetMapping("/new")
    @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT')")
    public String newForm(Model model) {
        model.addAttribute("activePage", "production-requests");
        model.addAttribute("recipes", requestService.recipesWithProducts());
        model.addAttribute("documentDate", LocalDate.now());
        return "production-requests/new";
    }

    /**
     * productId/batches — параллельные списки: строка формы №i задаёт productId.get(i) +
     * batches.get(i) (несколько позиций за одно действие, см. new.html — там несколько
     * одноимённых полей, Spring сам собирает их в списки в порядке появления в форме).
     * Название заявки не вводится вручную — позиции автоматически разбиваются по группе
     * товара, на каждую группу создаётся своя заявка (см. ProductionRequestService.createGrouped).
     * documentDate — дата документа для 1С (не задана в форме — сегодня, см. ProductionRequest.documentDate).
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT')")
    public String create(@RequestParam List<Long> productId, @RequestParam List<BigDecimal> batches,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate documentDate,
                          Authentication authentication, RedirectAttributes redirectAttributes) {
        try {
            if (productId.size() != batches.size()) {
                throw new IllegalArgumentException("Данные формы повреждены — не совпадает число позиций");
            }
            List<ProductionRequestService.Line> lines = new ArrayList<>();
            for (int i = 0; i < productId.size(); i++) {
                lines.add(new ProductionRequestService.Line(productId.get(i), batches.get(i)));
            }
            var created = requestService.createGrouped(lines, authentication.getName(), documentDate);
            String titles = created.stream().map(ProductionRequest::getTitle).collect(Collectors.joining("», «"));
            redirectAttributes.addFlashAttribute("success",
                    "Создано заявок: " + created.size() + " — «" + titles + "»");
            return "redirect:/production-requests";
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/production-requests/new";
        }
    }

    @GetMapping("/{id}")
    public String view(@PathVariable Long id, Model model) {
        model.addAttribute("activePage", "production-requests");
        ProductionRequest request = requestService.findById(id);
        model.addAttribute("request", request);
        model.addAttribute("ingredients", ingredientService.findAll());
        boolean canEditLines = request.getStatus() == ProductionRequestStatus.NEW
                || (request.getStatus() == ProductionRequestStatus.COLLECTED
                        && request.getLines().stream().noneMatch(ProductionRequestLine::isReceived));
        model.addAttribute("canEditLines", canEditLines);
        return "production-requests/view";
    }

    @GetMapping("/{id}/edit")
    @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT')")
    public String editForm(@PathVariable Long id, Model model) {
        model.addAttribute("activePage", "production-requests");
        model.addAttribute("request", requestService.findById(id));
        model.addAttribute("recipes", requestService.recipesWithProducts());
        return "production-requests/edit";
    }

    /** Название заявки не меняется — только позиции и дата документа (см. ProductionRequestService.update). */
    @PostMapping("/{id}/edit")
    @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT')")
    public String edit(@PathVariable Long id, @RequestParam List<Long> productId, @RequestParam List<BigDecimal> batches,
                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate documentDate,
                        Authentication authentication, RedirectAttributes redirectAttributes) {
        try {
            if (productId.size() != batches.size()) {
                throw new IllegalArgumentException("Данные формы повреждены — не совпадает число позиций");
            }
            List<ProductionRequestService.Line> lines = new ArrayList<>();
            for (int i = 0; i < productId.size(); i++) {
                lines.add(new ProductionRequestService.Line(productId.get(i), batches.get(i)));
            }
            requestService.update(id, lines, authentication.getName(), documentDate);
            return "redirect:/production-requests/" + id;
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/production-requests/" + id + "/edit";
        }
    }

    /**
     * Отмена заявки в ЛЮБОМ статусе, кроме уже отменённой — см. ProductionRequestService.cancel:
     * откатывает складские остатки (если были списаны/оприходованы) и помечает документы в 1С
     * на удаление, если они уже создавались. Раньше это было отдельной кнопкой «Удалить заявку»
     * (ADMIN-only, физически удаляла запись) — теперь объединено с отменой; только ADMIN,
     * т.к. может затрагивать реальные остатки склада.
     */
    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('ADMIN')")
    public String cancel(@PathVariable Long id, Authentication authentication, RedirectAttributes redirectAttributes) {
        try {
            requestService.cancel(id, authentication.getName());
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/production-requests/" + id;
    }

    @PostMapping("/{id}/collect")
    @PreAuthorize("hasAnyRole('ADMIN','WAREHOUSE_RAW')")
    public String collect(@PathVariable Long id, @RequestParam(required = false) List<Long> itemIds,
                           Authentication authentication, RedirectAttributes redirectAttributes) {
        Set<Long> confirmed = itemIds == null ? new HashSet<>() : new HashSet<>(itemIds);
        try {
            requestService.collect(id, confirmed, authentication.getName());
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/production-requests/" + id;
    }

    /**
     * Замена сырья в позиции — например, нужного не хватило на складе; либо, если ingredientId
     * не передан (форма "Изменить количество" в разметке), просто правка количества без замены
     * сырья (см. ProductionRequestService.substituteItem).
     */
    @PostMapping("/{id}/items/{itemId}/substitute")
    @PreAuthorize("hasAnyRole('ADMIN','WAREHOUSE_RAW')")
    public String substituteItem(@PathVariable Long id, @PathVariable Long itemId,
                                  @RequestParam(required = false) Long ingredientId,
                                  @RequestParam(required = false) String quantity,
                                  Authentication authentication, RedirectAttributes redirectAttributes) {
        try {
            // Пустая строка из формы (поле "Количество" необязательное) — не число, парсим
            // вручную вместо @RequestParam BigDecimal, иначе Spring падает с 400 на "".
            BigDecimal qty = (quantity == null || quantity.isBlank()) ? null : new BigDecimal(quantity);
            requestService.substituteItem(id, itemId, ingredientId, qty, authentication.getName());
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/production-requests/" + id;
    }

    /** Добавление совсем новой позиции сырья, которой не было в заявке (см. ProductionRequestService.addItem). */
    @PostMapping("/{id}/items/add")
    @PreAuthorize("hasAnyRole('ADMIN','WAREHOUSE_RAW')")
    public String addItem(@PathVariable Long id, @RequestParam Long ingredientId, @RequestParam BigDecimal quantity,
                           Authentication authentication, RedirectAttributes redirectAttributes) {
        try {
            requestService.addItem(id, ingredientId, quantity, authentication.getName());
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/production-requests/" + id;
    }

    /** Удаление позиции сырья из заявки (см. ProductionRequestService.removeItem). */
    @PostMapping("/{id}/items/{itemId}/delete")
    @PreAuthorize("hasAnyRole('ADMIN','WAREHOUSE_RAW')")
    public String removeItem(@PathVariable Long id, @PathVariable Long itemId,
                              Authentication authentication, RedirectAttributes redirectAttributes) {
        try {
            requestService.removeItem(id, itemId, authentication.getName());
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/production-requests/" + id;
    }

    /** Повторная ручная выгрузка в 1С — см. ProductionRequestService.resyncOneC. */
    @PostMapping("/{id}/sync-1c")
    @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT')")
    public String syncOneC(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            requestService.resyncOneC(id);
            redirectAttributes.addFlashAttribute("success", "Заявка №" + id + " синхронизирована с 1С");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Не удалось синхронизировать с 1С: " + e.getMessage());
        }
        return "redirect:/production-requests/" + id;
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
    public String completeOneC(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            requestService.completeRequisition(id);
            redirectAttributes.addFlashAttribute("success", "Требование-накладная заявки №" + id + " проведена в 1С");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Не удалось провести в 1С: " + e.getMessage());
        }
        return "redirect:/production-requests/" + id;
    }

    /**
     * "Отменить" (после "Завершить") — распроводит требование-накладную в 1С и снова разрешает
     * править состав сырья (см. ProductionRequestService.cancelCompletion).
     */
    @PostMapping("/{id}/cancel-completion")
    @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT','WAREHOUSE_RAW')")
    public String cancelCompletion(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            requestService.cancelCompletion(id);
            redirectAttributes.addFlashAttribute("success", "Проведение требования-накладной заявки №" + id + " отменено");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Не удалось отменить проведение в 1С: " + e.getMessage());
        }
        return "redirect:/production-requests/" + id;
    }

    @PostMapping("/{id}/lines/{lineId}/receive")
    @PreAuthorize("hasAnyRole('ADMIN','WAREHOUSE_FG')")
    public String receiveLine(@PathVariable Long id, @PathVariable Long lineId, @RequestParam BigDecimal quantity,
                               Authentication authentication, RedirectAttributes redirectAttributes) {
        try {
            requestService.receiveLine(id, lineId, quantity, authentication.getName());
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/production-requests/" + id;
    }

    /**
     * Отчёт по производству — норма (по рецепту) vs факт оприходования, отклонение. По
     * умолчанию за сегодня; {@code from}/{@code to} задают произвольный период, {@code all=true}
     * снимает ограничение по дате целиком ("скачать целиком" в PDF — весь накопленный отчёт).
     */
    @GetMapping("/report")
    @PreAuthorize("hasAnyRole('ADMIN','WAREHOUSE_FG','ACCOUNTANT')")
    public String report(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                          @RequestParam(defaultValue = "false") boolean all,
                          Model model) {
        LocalDate[] range = resolveRange(from, to, all);
        model.addAttribute("activePage", "production-requests");
        model.addAttribute("from", range[0]);
        model.addAttribute("to", range[1]);
        model.addAttribute("all", all);
        model.addAttribute("lines", requestService.report(range[0], range[1]));
        return "production-requests/report";
    }

    @GetMapping("/report/pdf")
    @PreAuthorize("hasAnyRole('ADMIN','WAREHOUSE_FG','ACCOUNTANT')")
    public ResponseEntity<byte[]> reportPdf(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                             @RequestParam(defaultValue = "false") boolean all) {
        LocalDate[] range = resolveRange(from, to, all);
        byte[] pdf = reportPdfService.generate(range[0], range[1], requestService.report(range[0], range[1]));
        String filename = "otchet_proizvodstvo_" + (range[0] == null ? "vse-vremya"
                : DateTimeFormatter.ISO_LOCAL_DATE.format(range[0]) + "_" + DateTimeFormatter.ISO_LOCAL_DATE.format(range[1])) + ".pdf";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + filename)
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    /** {from, to} — оба null означает "за всё время"; иначе не более одной пустой границы дозаполняется другой. */
    private LocalDate[] resolveRange(LocalDate from, LocalDate to, boolean all) {
        if (all) {
            return new LocalDate[]{null, null};
        }
        if (from == null && to == null) {
            LocalDate today = LocalDate.now();
            return new LocalDate[]{today, today};
        }
        return new LocalDate[]{from != null ? from : to, to != null ? to : from};
    }
}
