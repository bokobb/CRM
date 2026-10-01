package com.confectionery.crm.repository;

import com.confectionery.crm.domain.Employee;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EmployeeRepository extends JpaRepository<Employee, Long> {

    List<Employee> findAllByOrderByActiveDescFullNameAsc();
}
