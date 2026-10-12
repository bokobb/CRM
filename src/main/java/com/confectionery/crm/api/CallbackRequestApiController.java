package com.confectionery.crm.api;

import com.confectionery.crm.domain.CallbackRequest;
import com.confectionery.crm.domain.User;
import com.confectionery.crm.repository.UserRepository;
import com.confectionery.crm.service.ShopAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/** «Отклики и заявки» › «Заявки»: форма «Перезвоните мне» с сайта. Отрабатывают админ, бухгалтер и продажи. */
@RestController
@RequestMapping("/api/callback-requests")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT','SALES')")
public class CallbackRequestApiController {

    private final ShopAccessService shopAccessService;
    private final UserRepository userRepository;

    public record CallbackDto(Long id, String name, String phone, String business, String status, String note,
                              LocalDateTime createdAt, LocalDateTime handledAt, String handledBy) {
        static CallbackDto from(CallbackRequest r) {
            return new CallbackDto(r.getId(), r.getName(), r.getPhone(), r.getBusiness(), r.getStatus(), r.getNote(),
                    r.getCreatedAt(), r.getHandledAt(), r.getHandledBy());
        }
    }

    public record CallbackUpdate(String status, String note) {
    }

    @GetMapping
    public List<CallbackDto> list() {
        return shopAccessService.callbacks().stream().map(CallbackDto::from).toList();
    }

    @PutMapping("/{id}")
    public CallbackDto update(@PathVariable Long id, @RequestBody CallbackUpdate req, Authentication auth) {
        String who = userRepository.findByUsername(auth.getName()).map(User::getFullName).orElse(auth.getName());
        return CallbackDto.from(shopAccessService.updateCallback(id, req.status(), req.note(), who));
    }
}
