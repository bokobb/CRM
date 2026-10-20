package com.confectionery.crm.api;

import com.confectionery.crm.api.dto.CompanyProfileDto;
import com.confectionery.crm.service.CompanyProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/company-profile")
@RequiredArgsConstructor
public class CompanyProfileApiController {

    private final CompanyProfileService companyProfileService;
    private final com.confectionery.crm.service.OneCInvoiceService oneCInvoiceService;

    /** Склад в шапке «Реализации» 1С — так он назван в самой 1С. */
    @org.springframework.beans.factory.annotation.Value("${crm.invoice.warehouse-name:Гатовая Продукция}")
    private String warehouseName;

    @GetMapping
    public CompanyProfileDto get() {
        return CompanyProfileDto.from(companyProfileService.get())
                .withPrintInfo(warehouseName, oneCInvoiceService.warehouseManager());
    }

    @PutMapping
    @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT')")
    public CompanyProfileDto update(@RequestBody CompanyProfileDto req) {
        return CompanyProfileDto.from(companyProfileService.save(req.name(), req.address(), req.phone(), req.email(),
                req.pickupHours(), req.about(), req.legalInfo(), req.instagramUrl(), req.whatsappPhone(), req.tiktokUrl(),
                req.workHours(), req.jobsPhone(), req.mapPoint(), req.foundedYear(), req.externalLinks()));
    }
}
