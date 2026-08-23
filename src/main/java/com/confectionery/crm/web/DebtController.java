package com.confectionery.crm.web;

import com.confectionery.crm.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/debts")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ACCOUNTANT','ADMIN')")
public class DebtController {

    private final OrderService orderService;

    @GetMapping
    public String list(Model model) {
        model.addAttribute("activePage", "debts");
        model.addAttribute("debts", orderService.findUnpaidDebts());
        return "debts/list";
    }
}
