package com.confectionery.crm.web;

import com.confectionery.crm.domain.Client;
import com.confectionery.crm.service.ClientService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            clientService.delete(id);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Нельзя удалить клиента: у него есть заказы");
        }
        return "redirect:/clients";
    }
}
