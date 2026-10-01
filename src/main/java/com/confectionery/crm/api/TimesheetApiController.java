package com.confectionery.crm.api;

import com.confectionery.crm.service.TimesheetService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.YearMonth;
import java.util.List;

/** Табель сотрудников за месяц: просмотр, правка (перекличка), выгрузка в Excel. Деньги — только ADMIN/ACCOUNTANT. */
@RestController
@RequestMapping("/api/timesheet")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT','TECHNOLOGIST')")
public class TimesheetApiController {

    private final TimesheetService timesheetService;

    public record SaveRequest(List<TimesheetService.CellInput> cells) {
    }

    @GetMapping
    public TimesheetService.Sheet get(@RequestParam(required = false) String month, Authentication auth) {
        return timesheetService.month(parse(month), ProductionCostApiController.canSeeLabor(auth));
    }

    @PutMapping
    public TimesheetService.Sheet save(@RequestParam String month, @RequestBody SaveRequest req, Authentication auth) {
        YearMonth ym = parse(month);
        timesheetService.save(ym, req.cells() == null ? List.of() : req.cells(), auth.getName());
        return timesheetService.month(ym, ProductionCostApiController.canSeeLabor(auth));
    }

    @GetMapping("/xlsx")
    public ResponseEntity<byte[]> xlsx(@RequestParam(required = false) String month, Authentication auth) {
        YearMonth ym = parse(month);
        byte[] bytes = timesheetService.xlsx(ym, ProductionCostApiController.canSeeLabor(auth));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"tabel_" + ym + ".xlsx\"")
                .body(bytes);
    }

    private static YearMonth parse(String month) {
        return month == null || month.isBlank() ? YearMonth.now() : YearMonth.parse(month);
    }
}
