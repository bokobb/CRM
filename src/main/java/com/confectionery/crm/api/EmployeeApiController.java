package com.confectionery.crm.api;

import com.confectionery.crm.domain.Employee;
import com.confectionery.crm.domain.EmployeeCostGroup;
import com.confectionery.crm.service.EmployeeService;
import com.confectionery.crm.service.OneCEmployeeImportService;
import com.confectionery.crm.service.SalaryFundService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Меню «Сотрудники». Технолог ведёт список, но ставку за смену видят и задают только ADMIN/ACCOUNTANT. */
@RestController
@RequestMapping("/api/employees")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT','TECHNOLOGIST')")
public class EmployeeApiController {

    private final EmployeeService employeeService;
    private final OneCEmployeeImportService oneCEmployeeImportService;

    /**
     * costGroup — действующая группа затрат (заданная или по должности, тогда costGroupAuto=true).
     * monthlySalary/dailyRate/effectiveDailyRate — только ADMIN/ACCOUNTANT; effectiveDailyRate —
     * ставка за смену с учётом оклада (SalaryFundService.dailyRate) на текущий месяц.
     */
    public record EmployeeDto(Long id, String fullName, String position, String phone, BigDecimal dailyRate, boolean active,
                              String iin, LocalDate hiredAt, LocalDate birthDate, boolean fromOneC,
                              BigDecimal monthlySalary, BigDecimal effectiveDailyRate,
                              String costGroup, String costGroupLabel, boolean costGroupAuto) {
        static EmployeeDto from(Employee e, boolean showRate) {
            EmployeeCostGroup group = e.effectiveCostGroup();
            return new EmployeeDto(e.getId(), e.getFullName(), e.getPosition(), e.getPhone(),
                    showRate ? e.getDailyRate() : null, e.isActive(), e.getIin(), e.getHiredAt(), e.getBirthDate(),
                    e.getOneCKey() != null,
                    showRate ? e.getMonthlySalary() : null,
                    showRate ? SalaryFundService.dailyRate(e, LocalDate.now()) : null,
                    group.name(), group.getLabel(), e.getCostGroup() == null);
        }
    }

    /** costGroup: null/пусто — по должности. monthlySalary и costGroup меняют только ADMIN/ACCOUNTANT. */
    public record EmployeeRequest(String fullName, String position, String phone, BigDecimal dailyRate, Boolean active,
                                  BigDecimal monthlySalary, String costGroup) {
    }

    @GetMapping
    public List<EmployeeDto> list(Authentication auth) {
        boolean showRate = ProductionCostApiController.canSeeLabor(auth);
        return employeeService.findAll().stream().map(e -> EmployeeDto.from(e, showRate)).toList();
    }

    /** Кнопка «Загрузить из 1С»: добавляет/обновляет сотрудников по справочнику 1С (ставки не трогает). */
    @PostMapping("/import-1c")
    public OneCEmployeeImportService.ImportResult importFromOneC() {
        return oneCEmployeeImportService.importAll();
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
        EmployeeCostGroup group = req.costGroup() == null || req.costGroup().isBlank() ? null
                : EmployeeCostGroup.valueOf(req.costGroup());
        Employee e = employeeService.save(id, req.fullName(), req.position(), req.phone(), req.dailyRate(),
                req.active() == null || req.active(), showRate, req.monthlySalary(), group);
        return EmployeeDto.from(e, showRate);
    }
}
