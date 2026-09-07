package com.confectionery.crm.api;

import com.confectionery.crm.api.dto.CounterpartyDto;
import com.confectionery.crm.service.CounterpartyService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Только чтение — для выбора поставщика в форме закупки (см. mobile purchases_screen.dart). Управление — на вебе (/counterparties). */
@RestController
@RequestMapping("/api/counterparties")
@RequiredArgsConstructor
public class CounterpartyApiController {

    private final CounterpartyService counterpartyService;

    @GetMapping
    public List<CounterpartyDto> list() {
        return counterpartyService.findAll().stream().map(CounterpartyDto::from).toList();
    }
}
