package com.confectionery.crm.service;

import com.confectionery.crm.domain.Client;
import com.confectionery.crm.domain.ClientPrice;
import com.confectionery.crm.domain.Product;
import com.confectionery.crm.repository.ClientPriceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Индивидуальные цены дистрибьюторов. resolvePrice() — единственная точка, где решается,
 * какая цена товара действует для клиента: его собственный прайс, а если override не задан —
 * базовая цена товара. Используется при создании заказа (см. OrderService.createOrder).
 */
@Service
@RequiredArgsConstructor
public class ClientPriceService {

    private final ClientPriceRepository clientPriceRepository;
    private final ClientService clientService;
    private final ProductService productService;

    public List<ClientPrice> findByClient(Long clientId) {
        return clientPriceRepository.findByClientId(clientId);
    }

    public BigDecimal resolvePrice(Client client, Product product) {
        return clientPriceRepository.findByClientIdAndProductId(client.getId(), product.getId())
                .map(ClientPrice::getPrice)
                .orElse(product.getPrice());
    }

    @Transactional
    public ClientPrice upsert(Long clientId, Long productId, BigDecimal price) {
        Client client = clientService.findById(clientId);
        Product product = productService.findById(productId);
        ClientPrice clientPrice = clientPriceRepository.findByClientIdAndProductId(clientId, productId)
                .orElseGet(() -> {
                    ClientPrice cp = new ClientPrice();
                    cp.setClient(client);
                    cp.setProduct(product);
                    return cp;
                });
        clientPrice.setPrice(price);
        clientPrice.setUpdatedAt(LocalDateTime.now());
        return clientPriceRepository.save(clientPrice);
    }

    @Transactional
    public void delete(Long clientId, Long productId) {
        clientPriceRepository.findByClientIdAndProductId(clientId, productId)
                .ifPresent(clientPriceRepository::delete);
    }
}
