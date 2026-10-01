package com.confectionery.crm.api;

import com.confectionery.crm.api.dto.ProductionCostDto;
import com.confectionery.crm.service.ProductionBrigadeService;
import com.confectionery.crm.service.ProductionCostService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * Экран «Производство»: себестоимость за день по заявкам на сырьё, техкартам и бригадам.
 * Технолог видит всё, кроме ставок и зарплаты.
 */
@RestController
@RequestMapping("/api/production")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT','TECHNOLOGIST')")
public class ProductionCostApiController {

    private final ProductionCostService productionCostService;
    private final ProductionBrigadeService productionBrigadeService;

    public record BrigadeRequest(List<Long> employeeIds) {
    }

    @GetMapping("/cost")
    public ProductionCostDto day(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                 Authentication auth) {
        return productionCostService.day(date == null ? LocalDate.now() : date, canSeeLabor(auth));
    }

    @GetMapping("/requests/{id}")
    public ProductionCostDto.RequestDetail request(@PathVariable Long id, Authentication auth) {
        return productionCostService.request(id, canSeeLabor(auth));
    }

    @PutMapping("/requests/{id}/brigade")
    public ProductionCostDto.RequestDetail setBrigade(@PathVariable Long id, @RequestBody BrigadeRequest req, Authentication auth) {
        productionBrigadeService.setBrigade(id, req.employeeIds());
        return productionCostService.request(id, canSeeLabor(auth));
    }

    static boolean canSeeLabor(Authentication auth) {
        return auth.getAuthorities().stream().anyMatch(a ->
                a.getAuthority().equals("ROLE_ADMIN") || a.getAuthority().equals("ROLE_ACCOUNTANT"));
    }
}
