package com.confectionery.crm.web;

import com.confectionery.crm.api.dto.ProductionRequestSummaryDto;
import com.confectionery.crm.domain.ProductionRequestStatus;
import com.confectionery.crm.service.ProductionRequestService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.time.LocalDate;
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

    /**
     * Данные отдаются backend'ом без фильтрации — фильтр по товару и по дате (только для
     * колонки "Готово") целиком на стороне браузера (см. production/board.html), тем же
     * приёмом, что и на страницах "Рецепты"/"Сырьё"/"Клиенты": весь список уже на странице,
     * незачем гонять его туда-обратно на сервер ради фильтра. Колонка "Готово" по умолчанию
     * отфильтрована на сегодня ({@code today} — только чтобы проставить значение по умолчанию
     * в оба поля даты, сам фильтр всё равно считается в браузере) — кнопка "Показать всё" сбрасывает.
     */
    @GetMapping
    public String board(Model model) {
        List<ProductionRequestSummaryDto> all = requestService.findAll().stream()
                .map(ProductionRequestSummaryDto::from).toList();
        model.addAttribute("activePage", "production");
        model.addAttribute("pending", all.stream().filter(r -> r.status().equals(ProductionRequestStatus.NEW.name())).toList());
        model.addAttribute("inProgress", all.stream().filter(r -> r.status().equals(ProductionRequestStatus.COLLECTED.name())).toList());
        model.addAttribute("done", all.stream().filter(r -> r.status().equals(ProductionRequestStatus.RECEIVED.name())).toList());
        model.addAttribute("today", LocalDate.now());
        return "production/board";
    }
}
