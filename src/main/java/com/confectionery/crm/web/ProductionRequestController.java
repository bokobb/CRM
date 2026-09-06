package com.confectionery.crm.web;

import com.confectionery.crm.api.dto.ProductionRequestSummaryDto;
import com.confectionery.crm.domain.ProductionRequest;
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

    /** По умолчанию показываются заявки, созданные сегодня — {@code all=true} снимает фильтр по дате. */
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
                .filter(r -> filterDate == null || filterDate.equals(r.createdAt().toLocalDate()))
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
        return "production-requests/new";
    }

    /**
     * productId/batches — параллельные списки: строка формы №i задаёт productId.get(i) +
     * batches.get(i) (несколько позиций за одно действие, см. new.html — там несколько
     * одноимённых полей, Spring сам собирает их в списки в порядке появления в форме).
     * Название заявки не вводится вручную — позиции автоматически разбиваются по группе
     * товара, на каждую группу создаётся своя заявка (см. ProductionRequestService.createGrouped).
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT')")
    public String create(@RequestParam List<Long> productId, @RequestParam List<BigDecimal> batches,
                          Authentication authentication, RedirectAttributes redirectAttributes) {
        try {
            if (productId.size() != batches.size()) {
                throw new IllegalArgumentException("Данные формы повреждены — не совпадает число позиций");
            }
            List<ProductionRequestService.Line> lines = new ArrayList<>();
            for (int i = 0; i < productId.size(); i++) {
                lines.add(new ProductionRequestService.Line(productId.get(i), batches.get(i)));
            }
            var created = requestService.createGrouped(lines, authentication.getName());
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
        model.addAttribute("request", requestService.findById(id));
        model.addAttribute("ingredients", ingredientService.findAll());
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

    /** Название заявки не меняется — только позиции (см. ProductionRequestService.update). */
    @PostMapping("/{id}/edit")
    @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT')")
    public String edit(@PathVariable Long id, @RequestParam List<Long> productId, @RequestParam List<BigDecimal> batches,
                        Authentication authentication, RedirectAttributes redirectAttributes) {
        try {
            if (productId.size() != batches.size()) {
                throw new IllegalArgumentException("Данные формы повреждены — не совпадает число позиций");
            }
            List<ProductionRequestService.Line> lines = new ArrayList<>();
            for (int i = 0; i < productId.size(); i++) {
                lines.add(new ProductionRequestService.Line(productId.get(i), batches.get(i)));
            }
            requestService.update(id, lines, authentication.getName());
            return "redirect:/production-requests/" + id;
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/production-requests/" + id + "/edit";
        }
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT')")
    public String cancel(@PathVariable Long id, Authentication authentication, RedirectAttributes redirectAttributes) {
        try {
            requestService.cancel(id, authentication.getName());
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/production-requests/" + id;
    }

    /**
     * Полное удаление заявки в ЛЮБОМ статусе (в отличие от cancel — только для «Новой») — см.
     * ProductionRequestService.delete: откатывает складские остатки и помечает документы в 1С
     * на удаление, если они уже создавались. Только ADMIN — необратимо.
     */
    @PostMapping("/{id}/delete")
    @PreAuthorize("hasRole('ADMIN')")
    public String delete(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            requestService.delete(id);
            redirectAttributes.addFlashAttribute("success", "Заявка №" + id + " удалена, остатки пересчитаны");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Не удалось удалить заявку: " + e.getMessage());
            return "redirect:/production-requests/" + id;
        }
        return "redirect:/production-requests";
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

    /** Замена сырья в позиции — например, нужного не хватило на складе (см. ProductionRequestService.substituteItem). */
    @PostMapping("/{id}/items/{itemId}/substitute")
    @PreAuthorize("hasAnyRole('ADMIN','WAREHOUSE_RAW')")
    public String substituteItem(@PathVariable Long id, @PathVariable Long itemId,
                                  @RequestParam Long ingredientId,
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

    /** Повторная ручная выгрузка в 1С — см. ProductionRequestService.resyncOneC. */
    @PostMapping("/{id}/sync-1c")
    @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT')")
    public String syncOneC(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            requestService.resyncOneC(id);
            redirectAttributes.addFlashAttribute("success", "Заявка №" + id + " выгружена в 1С");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Не удалось выгрузить в 1С: " + e.getMessage());
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

    /** Дневной отчёт "сколько сегодня изготовили" — для зав. склада ГП, кнопка "Отчёт". */
    @GetMapping("/report")
    @PreAuthorize("hasAnyRole('ADMIN','WAREHOUSE_FG','ACCOUNTANT')")
    public String report(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                          Model model) {
        LocalDate d = date != null ? date : LocalDate.now();
        model.addAttribute("activePage", "production-requests");
        model.addAttribute("date", d);
        model.addAttribute("lines", requestService.dailyReport(d));
        return "production-requests/report";
    }

    @GetMapping("/report/pdf")
    @PreAuthorize("hasAnyRole('ADMIN','WAREHOUSE_FG','ACCOUNTANT')")
    public ResponseEntity<byte[]> reportPdf(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate d = date != null ? date : LocalDate.now();
        byte[] pdf = reportPdfService.generate(d, requestService.dailyReport(d));
        String filename = "otchet_" + DateTimeFormatter.ISO_LOCAL_DATE.format(d) + ".pdf";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + filename)
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }
}
