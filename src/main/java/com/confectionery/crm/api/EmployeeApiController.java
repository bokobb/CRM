package com.confectionery.crm.api;

import com.confectionery.crm.domain.Employee;
import com.confectionery.crm.service.EmployeeService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/** Меню «Сотрудники». Технолог ведёт список, но ставку за смену видят и задают только ADMIN/ACCOUNTANT. */
@RestController
@RequestMapping("/api/employees")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT','TECHNOLOGIST')")
public class EmployeeApiController {

    private final EmployeeService employeeService;

    public record EmployeeDto(Long id, String fullName, String position, String phone, BigDecimal dailyRate, boolean active) {
        static EmployeeDto from(Employee e, boolean showRate) {
            return new EmployeeDto(e.getId(), e.getFullName(), e.getPosition(), e.getPhone(),
                    showRate ? e.getDailyRate() : null, e.isActive());
        }
    }

    public record EmployeeRequest(String fullName, String position, String phone, BigDecimal dailyRate, Boolean active) {
    }

    @GetMapping
    public List<EmployeeDto> list(Authentication auth) {
        boolean showRate = ProductionCostApiController.canSeeLabor(auth);
        return employeeService.findAll().stream().map(e -> EmployeeDto.from(e, showRate)).toList();
    }

    @PostMapping
    public EmployeeDto create(@RequestBody EmployeeRequest req, Authentication auth) {
        return save(null, req, auth);
    }

    @PutMapping("/{id}")
    public EmployeeDto update(@PathVariable Long id, @RequestBody EmployeeRequest req, Authentication auth) {
        return save(id, req, auth);
    }

    private EmployeeDto save(Long id, EmployeeRequest req, Authentication auth) {
        boolean showRate = ProductionCostApiController.canSeeLabor(auth);
        Employee e = employeeService.save(id, req.fullName(), req.position(), req.phone(), req.dailyRate(),
                req.active() == null || req.active(), showRate);
        return EmployeeDto.from(e, showRate);
    }
}
