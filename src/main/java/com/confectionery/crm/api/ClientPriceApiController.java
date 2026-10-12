package com.confectionery.crm.api;

import com.confectionery.crm.api.dto.ClientPriceDto;
import com.confectionery.crm.api.dto.ClientPriceRequest;
import com.confectionery.crm.service.ClientPriceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/clients/{clientId}/prices")
@RequiredArgsConstructor
public class ClientPriceApiController {

    private final ClientPriceService clientPriceService;
    private final com.confectionery.crm.service.OrderPriceFillService orderPriceFillService;

    @GetMapping
    public List<ClientPriceDto> list(@PathVariable Long clientId) {
        return clientPriceService.findByClient(clientId).stream().map(ClientPriceDto::from).toList();
    }

    @PutMapping("/{productId}")
    @PreAuthorize("hasAnyRole('SALES','ADMIN','ACCOUNTANT')")
    public ClientPriceDto upsert(@PathVariable Long clientId, @PathVariable Long productId,
                                  @Valid @RequestBody ClientPriceRequest req) {
        ClientPriceDto saved = ClientPriceDto.from(clientPriceService.upsert(clientId, productId, req.price()));
        orderPriceFillService.afterPriceChange(clientId, productId);   // нули в его неотгруженных заказах
        return saved;
    }

    @DeleteMapping("/{productId}")
    @PreAuthorize("hasAnyRole('SALES','ADMIN','ACCOUNTANT')")
    public void delete(@PathVariable Long clientId, @PathVariable Long productId) {
        clientPriceService.delete(clientId, productId);
    }
}
