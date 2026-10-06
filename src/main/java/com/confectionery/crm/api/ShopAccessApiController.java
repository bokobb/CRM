package com.confectionery.crm.api;

import com.confectionery.crm.domain.ShopAccessRequest;
import com.confectionery.crm.service.ShopAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/** Заявки на доступ к сайту клиентов с новых номеров — только ADMIN, см. ShopAccessService. */
@RestController
@RequestMapping("/api/shop-access-requests")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class ShopAccessApiController {

    private final ShopAccessService shopAccessService;

    public record ShopAccessRequestDto(Long id, String phone, String name, String status, LocalDateTime createdAt, Long clientId) {
        static ShopAccessRequestDto from(ShopAccessRequest r) {
            return new ShopAccessRequestDto(r.getId(), r.getPhone(), r.getName(), r.getStatus(), r.getCreatedAt(), r.getClientId());
        }
    }

    @GetMapping
    public List<ShopAccessRequestDto> pending() {
        return shopAccessService.pending().stream().map(ShopAccessRequestDto::from).toList();
    }

    @PostMapping("/{id}/approve")
    public ShopAccessRequestDto approve(@PathVariable Long id, Authentication authentication) {
        return ShopAccessRequestDto.from(shopAccessService.approve(id, authentication.getName()));
    }

    @PostMapping("/{id}/reject")
    public ShopAccessRequestDto reject(@PathVariable Long id, Authentication authentication) {
        return ShopAccessRequestDto.from(shopAccessService.reject(id, authentication.getName()));
    }
}
