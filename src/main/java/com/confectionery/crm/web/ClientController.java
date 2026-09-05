package com.confectionery.crm.web;

import com.confectionery.crm.domain.Client;
import com.confectionery.crm.service.ClientPriceService;
import com.confectionery.crm.service.ClientService;
import com.confectionery.crm.service.OneCClientLinkService;
import com.confectionery.crm.service.OneCClientPriceSyncService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/clients")
@RequiredArgsConstructor
public class ClientController {

    private final ClientService clientService;
    private final ClientPriceService clientPriceService;
    private final OneCClientLinkService oneCClientLinkService;
    private final OneCClientPriceSyncService oneCClientPriceSyncService;

    @GetMapping
    public String list(Model model) {
        model.addAttribute("activePage", "clients");
        model.addAttribute("clients", clientService.findAll());
        return "clients/list";
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("activePage", "clients");
        model.addAttribute("client", new Client());
        return "clients/form";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        model.addAttribute("activePage", "clients");
        model.addAttribute("client", clientService.findById(id));
        return "clients/form";
    }

    @GetMapping("/{id}")
    public String view(@PathVariable Long id, Model model) {
        model.addAttribute("activePage", "clients");
        model.addAttribute("client", clientService.findById(id));
        model.addAttribute("prices", clientPriceService.findByClient(id));
        return "clients/view";
    }

    @PostMapping
    public String save(@Valid @ModelAttribute("client") Client client, BindingResult binding, Model model) {
        if (binding.hasErrors()) {
            model.addAttribute("activePage", "clients");
            return "clients/form";
        }
        clientService.save(client);
        return "redirect:/clients";
    }

    /** Только ADMIN — удаление клиента каскадно стирает всю его историю (заказы, цены, WhatsApp), см. ClientService.delete. */
    @PostMapping("/{id}/delete")
    @PreAuthorize("hasRole('ADMIN')")
    public String delete(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            clientService.delete(id);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Не удалось удалить клиента: " + e.getMessage());
        }
        return "redirect:/clients";
    }

    /** Автопривязка всех ещё не привязанных клиентов к 1С по точному совпадению названия (см. OneCClientLinkService). */
    @PostMapping("/link-1c")
    @PreAuthorize("hasRole('ADMIN')")
    public String autoLinkOneC(RedirectAttributes redirectAttributes) {
        try {
            var result = oneCClientLinkService.autoLinkAll();
            String message = "Привязано клиентов к 1С: " + result.linked();
            if (!result.unmatched().isEmpty()) {
                message += ". Не найдено в 1С по названию: " + String.join(", ", result.unmatched());
            }
            redirectAttributes.addFlashAttribute("success", message);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Не удалось привязать клиентов к 1С: " + e.getMessage());
        }
        return "redirect:/clients";
    }

    /** Ручная привязка одного клиента к 1С — по подстроке названия, если автопривязка не сработала. */
    @PostMapping("/{id}/link-1c")
    @PreAuthorize("hasRole('ADMIN')")
    public String linkOneC(@PathVariable Long id, @RequestParam String query, RedirectAttributes redirectAttributes) {
        try {
            oneCClientLinkService.linkByQuery(id, query);
            redirectAttributes.addFlashAttribute("success", "Клиент привязан к 1С");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Не удалось привязать клиента к 1С: " + e.getMessage());
        }
        return "redirect:/clients/" + id;
    }

    @PostMapping("/{id}/unlink-1c")
    @PreAuthorize("hasRole('ADMIN')")
    public String unlinkOneC(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            oneCClientLinkService.unlink(id);
            redirectAttributes.addFlashAttribute("success", "Привязка к 1С снята");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Не удалось снять привязку: " + e.getMessage());
        }
        return "redirect:/clients/" + id;
    }

    /**
     * Обновляет индивидуальный прайс всех клиентов из 1С — там у каждого клиента свой именной
     * "Тип цен номенклатуры" (см. OneCClientPriceSyncService), а не через договор.
     */
    @PostMapping("/prices-1c")
    @PreAuthorize("hasRole('ADMIN')")
    public String syncPricesOneC(RedirectAttributes redirectAttributes) {
        try {
            var result = oneCClientPriceSyncService.syncAll();
            String message = "Обновлены цены у клиентов: " + result.clientsUpdated()
                    + " (позиций: " + result.pricesUpdated() + ")";
            if (!result.clientsWithoutPriceType().isEmpty()) {
                message += ". Не найден тип цен в 1С: " + String.join(", ", result.clientsWithoutPriceType());
            }
            redirectAttributes.addFlashAttribute("success", message);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Не удалось обновить цены из 1С: " + e.getMessage());
        }
        return "redirect:/clients";
    }

    /** Ручная привязка типа цен клиента в 1С — по подстроке названия, если автопривязка не сработала. */
    @PostMapping("/{id}/link-price-1c")
    @PreAuthorize("hasRole('ADMIN')")
    public String linkPriceOneC(@PathVariable Long id, @RequestParam String query, RedirectAttributes redirectAttributes) {
        try {
            oneCClientPriceSyncService.linkPriceTypeByQuery(id, query);
            redirectAttributes.addFlashAttribute("success", "Тип цен привязан, прайс обновлён");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Не удалось привязать тип цен: " + e.getMessage());
        }
        return "redirect:/clients/" + id;
    }
}
