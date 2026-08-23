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

    @GetMapping
    public CompanyProfileDto get() {
        return CompanyProfileDto.from(companyProfileService.get());
    }

    @PutMapping
    @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT')")
    public CompanyProfileDto update(@RequestBody CompanyProfileDto req) {
        return CompanyProfileDto.from(companyProfileService.save(req.name(), req.address(), req.phone(), req.email()));
    }
}
