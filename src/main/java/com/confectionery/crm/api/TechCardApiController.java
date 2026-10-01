package com.confectionery.crm.api;

import com.confectionery.crm.api.dto.TechCardDto;
import com.confectionery.crm.api.dto.TechCardRequest;
import com.confectionery.crm.api.dto.TechCardSummaryDto;
import com.confectionery.crm.service.TechCardService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Техкарты товаров — экран «Производство» в мобильном приложении. Править может только ADMIN. */
@RestController
@RequestMapping("/api/tech-cards")
@RequiredArgsConstructor
public class TechCardApiController {

    private final TechCardService techCardService;

    @GetMapping
    public List<TechCardSummaryDto> list() {
        return techCardService.list();
    }

    @GetMapping("/{productId}")
    public TechCardDto get(@PathVariable Long productId) {
        return techCardService.get(productId);
    }

    @PutMapping("/{productId}")
    @PreAuthorize("hasAnyRole('ADMIN','TECHNOLOGIST')")
    public TechCardDto save(@PathVariable Long productId, @RequestBody TechCardRequest req) {
        return techCardService.save(productId, req);
    }
}
