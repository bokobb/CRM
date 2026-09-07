package com.confectionery.crm.web;

import com.confectionery.crm.service.CounterpartyService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Справочник контрагентов (поставщиков) — см. CounterpartyService. Тот же доступ, что и у закупок. */
@Controller
@RequestMapping("/counterparties")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','WAREHOUSE_RAW')")
public class CounterpartyController {

    private final CounterpartyService counterpartyService;

    @GetMapping
    public String list(Model model) {
        model.addAttribute("activePage", "counterparties");
        model.addAttribute("counterparties", counterpartyService.findAll());
        return "counterparties/list";
    }

    @PostMapping
    public String create(@RequestParam String name, RedirectAttributes redirectAttributes) {
        try {
            counterpartyService.create(name);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/counterparties";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            counterpartyService.delete(id);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/counterparties";
    }

    /** Разовая загрузка всех контрагентов из 1С — см. CounterpartyService.syncFromOneC. */
    @PostMapping("/sync-1c")
    public String syncOneC(RedirectAttributes redirectAttributes) {
        try {
            var result = counterpartyService.syncFromOneC();
            redirectAttributes.addFlashAttribute("success",
                    "Загружено из 1С: " + result.totalInOneC() + ", новых — " + result.created()
                            + ", обновлено имён — " + result.updated());
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Не удалось загрузить из 1С: " + e.getMessage());
        }
        return "redirect:/counterparties";
    }
}
