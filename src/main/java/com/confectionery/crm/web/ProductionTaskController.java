package com.confectionery.crm.web;

import com.confectionery.crm.domain.ProductionTaskStatus;
import com.confectionery.crm.service.ProductionTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/production")
@RequiredArgsConstructor
public class ProductionTaskController {

    private final ProductionTaskService productionTaskService;

    @GetMapping
    public String board(Model model) {
        model.addAttribute("activePage", "production");
        model.addAttribute("pending", productionTaskService.findByStatus(ProductionTaskStatus.PENDING));
        model.addAttribute("inProgress", productionTaskService.findByStatus(ProductionTaskStatus.IN_PROGRESS));
        model.addAttribute("done", productionTaskService.findByStatus(ProductionTaskStatus.DONE));
        return "production/board";
    }

    @PostMapping("/{id}/start")
    public String start(@PathVariable Long id, @RequestParam(required = false) String assignedTo) {
        productionTaskService.start(id, assignedTo);
        return "redirect:/production";
    }

    @PostMapping("/{id}/complete")
    public String complete(@PathVariable Long id) {
        productionTaskService.complete(id);
        return "redirect:/production";
    }
}
