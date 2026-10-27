package com.hrms.employee.repository;

import com.hrms.employee.entity.Employee;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.*;

public interface EmployeeRepository extends MongoRepository<Employee, String> {
    List<Employee> findByManagerId(String managerId);
    Optional<Employee> findByEmailIgnoreCase(String email);
    Optional<Employee> findByEmployeeId(String employeeId);
    boolean existsByEmailIgnoreCase(String email);
    boolean existsByEmployeeId(String employeeId);
    List<Employee> findByNameContainingIgnoreCaseAndDepartmentContainingIgnoreCase(String name, String department);
    List<Employee> findByNameContainingIgnoreCase(String name);
    List<Employee> findByDepartmentContainingIgnoreCase(String department);
}
