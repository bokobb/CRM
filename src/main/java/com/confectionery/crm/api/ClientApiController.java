package com.confectionery.crm.api;

import com.confectionery.crm.api.dto.ClientDto;
import com.confectionery.crm.api.dto.ClientRequest;
import com.confectionery.crm.domain.Client;
import com.confectionery.crm.repository.OrderRepository;
import com.confectionery.crm.service.ClientService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/clients")
@RequiredArgsConstructor
public class ClientApiController {

    private final ClientService clientService;
    private final OrderRepository orderRepository;

    @GetMapping
    public List<ClientDto> list() {
        return clientService.findAll().stream().map(ClientDto::from).toList();
    }

    @GetMapping("/{id}")
    public ClientDto get(@PathVariable Long id) {
        return ClientDto.from(clientService.findById(id));
    }

    @PostMapping
    public ClientDto create(@Valid @RequestBody ClientRequest req) {
        return ClientDto.from(clientService.save(toEntity(new Client(), req)));
    }

    @PutMapping("/{id}")
    public ClientDto update(@PathVariable Long id, @Valid @RequestBody ClientRequest req) {
        Client client = clientService.findById(id);
        return ClientDto.from(clientService.save(toEntity(client, req)));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        clientService.delete(id);
    }

    /** Сумма покупок клиента за текущий календарный месяц (по дате отгрузки/завершения). */
    @GetMapping("/{id}/monthly-sales")
    public BigDecimal monthlySales(@PathVariable Long id) {
        LocalDate monthStart = LocalDate.now().withDayOfMonth(1);
        LocalDateTime start = monthStart.atStartOfDay();
        LocalDateTime end = monthStart.plusMonths(1).atStartOfDay();
        return orderRepository.sumSalesForClientBetween(id, start, end);
    }

    private Client toEntity(Client client, ClientRequest req) {
        client.setName(req.name());
        client.setPhone(req.phone());
        client.setEmail(req.email());
        client.setAddress(req.address());
        client.setNotes(req.notes());
        return client;
    }
}
