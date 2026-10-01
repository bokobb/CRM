package com.confectionery.crm.service;

import com.confectionery.crm.domain.Employee;
import com.confectionery.crm.repository.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/** Сотрудники цеха (меню «Сотрудники»). Ставку меняет только тот, кому она видна (canSeeRates). */
@Service
@RequiredArgsConstructor
public class EmployeeService {

    private final EmployeeRepository employeeRepository;

    @Transactional(readOnly = true)
    public List<Employee> findAll() {
        return employeeRepository.findAllByOrderByActiveDescFullNameAsc();
    }

    @Transactional
    public Employee save(Long id, String fullName, String position, String phone, BigDecimal dailyRate,
                         boolean active, boolean canSetRate) {
        if (fullName == null || fullName.isBlank()) {
            throw new IllegalArgumentException("Укажите ФИО сотрудника");
        }
        if (dailyRate != null && dailyRate.signum() < 0) {
            throw new IllegalArgumentException("Ставка не может быть отрицательной");
        }
        Employee e = id == null ? new Employee()
                : employeeRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Сотрудник не найден"));
        e.setFullName(fullName.trim());
        e.setPosition(blankToNull(position));
        e.setPhone(blankToNull(phone));
        e.setActive(active);
        if (canSetRate) {
            e.setDailyRate(dailyRate);
        }
        return employeeRepository.save(e);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
