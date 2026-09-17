package com.confectionery.crm.api;

import com.confectionery.crm.api.dto.CounterpartyCreateRequest;
import com.confectionery.crm.api.dto.CounterpartyDto;
import com.confectionery.crm.service.CounterpartyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Список — для выбора поставщика в форме закупки (см. mobile purchases_screen.dart), доступен
 * всем. Создание — те же роли, что и на вебе (см. CounterpartyController), нужно мобильному
 * экрану "Настройки → Контрагенты" (см. mobile counterparties_screen.dart). Удаление и разовая
 * загрузка из 1С по-прежнему только на вебе (/counterparties) — не запрошено для мобильного.
 */
@RestController
@RequestMapping("/api/counterparties")
@RequiredArgsConstructor
public class CounterpartyApiController {

    private final CounterpartyService counterpartyService;

    @GetMapping
    public List<CounterpartyDto> list() {
        return counterpartyService.findAll().stream().map(CounterpartyDto::from).toList();
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','WAREHOUSE_RAW')")
    public CounterpartyDto create(@Valid @RequestBody CounterpartyCreateRequest req) {
        return CounterpartyDto.from(counterpartyService.create(req.name()));
    }
}
