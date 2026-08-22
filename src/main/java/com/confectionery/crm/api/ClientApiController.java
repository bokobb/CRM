package com.confectionery.crm.api;

import com.confectionery.crm.api.dto.ClientDto;
import com.confectionery.crm.api.dto.ClientRequest;
import com.confectionery.crm.domain.Client;
import com.confectionery.crm.service.ClientService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/clients")
@RequiredArgsConstructor
public class ClientApiController {

    private final ClientService clientService;

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

    private Client toEntity(Client client, ClientRequest req) {
        client.setName(req.name());
        client.setPhone(req.phone());
        client.setEmail(req.email());
        client.setAddress(req.address());
        client.setNotes(req.notes());
        return client;
    }
}
