package com.hrms.service;

import com.hrms.dto.EmployeeRequest;
import com.hrms.model.Employee;
import com.hrms.repository.EmployeeRepository;
import com.hrms.repository.UserRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class EmployeeService {
    private final EmployeeRepository employees;
    private final EmployeeIdGenerator ids;
    private final AutocompleteService autocomplete;
    private final UserRepository users;

    public EmployeeService(EmployeeRepository employees, EmployeeIdGenerator ids, AutocompleteService autocomplete, UserRepository users) {
        this.employees = employees; this.ids = ids; this.autocomplete = autocomplete; this.users = users;
    }

    public List<Employee> list(String q) {
        if (q == null || q.isBlank()) return employees.findAll(Sort.by(Sort.Direction.ASC, "fullName"));
        String value = q.trim();
        return employees.findByFullNameContainingIgnoreCaseOrDepartmentContainingIgnoreCaseOrJobTitleContainingIgnoreCase(
            value, value, value);
    }

    @Cacheable(value = "employeeProfiles", key = "#employeeId")
    public Employee getByEmployeeId(String employeeId) {
        return employees.findByEmployeeId(employeeId)
            .orElseThrow(() -> new IllegalArgumentException("Employee not found: " + employeeId));
    }

    @CacheEvict(value = "employeeProfiles", allEntries = true)
    public Employee create(EmployeeRequest request) {
        if (employees.existsByEmailIgnoreCase(request.email()) || users.existsByEmailIgnoreCase(request.email()))
            throw new IllegalArgumentException("Employee email already exists");
        Employee e = new Employee(ids.nextId(), request.fullName().trim(), request.department().trim(),
            request.jobTitle().trim(), request.email().trim().toLowerCase(),
            request.phone() == null ? "" : request.phone().trim(),
            request.location() == null ? "" : request.location().trim());
        Employee saved = employees.save(e);
        autocomplete.rebuild();
        return saved;
    }

    @CacheEvict(value = "employeeProfiles", allEntries = true)
    public Employee update(String employeeId, EmployeeRequest request) {
        Employee e = getByEmployeeId(employeeId);
        String newEmail = request.email().trim().toLowerCase();
        if (!e.getEmail().equalsIgnoreCase(newEmail) && employees.existsByEmailIgnoreCase(newEmail))
            throw new IllegalArgumentException("Employee email already exists");
        String oldEmail = e.getEmail();
        e.setFullName(request.fullName().trim());
        e.setDepartment(request.department().trim());
        e.setJobTitle(request.jobTitle().trim());
        e.setEmail(request.email().trim().toLowerCase());
        e.setPhone(request.phone() == null ? "" : request.phone().trim());
        e.setLocation(request.location() == null ? "" : request.location().trim());
        Employee saved = employees.save(e);
        users.findByEmailIgnoreCase(oldEmail).ifPresent(user -> { user.setEmail(newEmail); users.save(user); });
        autocomplete.rebuild();
        return saved;
    }

    @CacheEvict(value = "employeeProfiles", allEntries = true)
    public void delete(String employeeId) {
        Employee e = getByEmployeeId(employeeId);
        employees.delete(e);
        autocomplete.rebuild();
    }
}
