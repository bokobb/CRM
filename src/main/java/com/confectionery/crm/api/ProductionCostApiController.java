package com.confectionery.crm.api;

import com.confectionery.crm.api.dto.ProductionCostDto;
import com.confectionery.crm.service.ProductionCostService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** Экран «Производство»: себестоимость за день по заявкам на сырьё и техкартам. */
@RestController
@RequestMapping("/api/production/cost")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT')")
public class ProductionCostApiController {

    private final ProductionCostService productionCostService;

    @GetMapping
    public ProductionCostDto day(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return productionCostService.day(date == null ? LocalDate.now() : date);
    }
}
