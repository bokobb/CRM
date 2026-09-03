package com.confectionery.crm.web;

import com.confectionery.crm.api.dto.ProductionRequestSummaryDto;
import com.confectionery.crm.domain.ProductionRequestStatus;
import com.confectionery.crm.service.ProductionRequestService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;

/**
 * Доска "Производство": те же заявки на сырьё (см. ProductionRequestService), что и на
 * странице "Заявки на сырьё", но сгруппированные по стадии в три колонки — Ожидает/В работе/
 * Готово (= статусы NEW/COLLECTED/RECEIVED). Карточка ведёт на ту же страницу заявки, где и
 * происходят реальные действия (сборка по чек-листу, оприходование по строкам) — здесь только
 * обзорная витрина. Раньше этот маршрут показывал ProductionTask (цеховые задачи по заказам
 * клиентов) — но такие задачи нигде в коде не создавались (не было ни одного вызова
 * `new ProductionTask()`), доска была всегда пустой и фактически не использовалась.
 */
@Controller
@RequestMapping("/production")
@RequiredArgsConstructor
public class ProductionController {

    private final ProductionRequestService requestService;

    @GetMapping
    public String board(Model model) {
        List<ProductionRequestSummaryDto> all = requestService.findAll().stream()
                .map(ProductionRequestSummaryDto::from).toList();
        model.addAttribute("activePage", "production");
        model.addAttribute("pending", all.stream().filter(r -> r.status().equals(ProductionRequestStatus.NEW.name())).toList());
        model.addAttribute("inProgress", all.stream().filter(r -> r.status().equals(ProductionRequestStatus.COLLECTED.name())).toList());
        model.addAttribute("done", all.stream().filter(r -> r.status().equals(ProductionRequestStatus.RECEIVED.name())).toList());
        return "production/board";
    }
}
