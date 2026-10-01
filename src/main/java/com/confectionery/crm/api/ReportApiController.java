package com.confectionery.crm.api;

import com.confectionery.crm.api.dto.ReportDto;
import com.confectionery.crm.service.ReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/** Меню «Отчёты»: список доступных роли отчётов и сам отчёт за день/период (см. ReportService). */
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportApiController {

    private final ReportService reportService;

    @GetMapping
    public List<ReportDto.Info> list(Authentication auth) {
        return ReportService.available(role(auth)).stream().map(ReportService.Kind::info).toList();
    }

    @GetMapping("/{key}")
    public ReportDto get(@PathVariable String key,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                         Authentication auth) {
        ReportService.Kind kind = ReportService.Kind.of(key);
        if (!ReportService.available(role(auth)).contains(kind)) {
            throw new AccessDeniedException("Этот отчёт вам недоступен");
        }
        LocalDate f = from != null ? from : LocalDate.now();
        LocalDate t = to != null ? to : f;
        return reportService.build(kind, f, t, ProductionCostApiController.canSeeLabor(auth));
    }

    private static String role(Authentication auth) {
        return auth.getAuthorities().stream().map(a -> a.getAuthority())
                .filter(a -> a.startsWith("ROLE_")).map(a -> a.substring(5)).findFirst().orElse(null);
    }
}
