package com.hrms.controller;

import com.hrms.dto.EmployeeRequest;
import com.hrms.model.Employee;
import com.hrms.model.UserAccount;
import com.hrms.repository.UserRepository;
import com.hrms.service.AutocompleteService;
import com.hrms.service.EmployeeService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/employees")
public class EmployeeController {
    private final EmployeeService service;
    private final AutocompleteService autocomplete;
    private final UserRepository users;

    public EmployeeController(EmployeeService service, AutocompleteService autocomplete, UserRepository users) {
        this.service = service; this.autocomplete = autocomplete; this.users = users;
    }

    @GetMapping
    public List<Employee> list(@RequestParam(required = false) String q) { return service.list(q); }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','HR')")
    public Employee create(@Valid @RequestBody EmployeeRequest request) { return service.create(request); }

    @GetMapping("/{employeeId}")
    public Employee get(@PathVariable String employeeId, Authentication auth) {
        UserAccount user = users.findByEmailIgnoreCase(auth.getName()).orElseThrow();
        if (!user.getRole().name().equals("ADMIN") && !user.getRole().name().equals("HR")
            && !user.getEmployeeId().equals(employeeId))
            throw new org.springframework.security.access.AccessDeniedException("You can only view your own profile");
        return service.getByEmployeeId(employeeId);
    }

    @PutMapping("/{employeeId}")
    public Employee update(@PathVariable String employeeId, @Valid @RequestBody EmployeeRequest request,
                           Authentication auth) {
        UserAccount user = users.findByEmailIgnoreCase(auth.getName()).orElseThrow();
        if (!user.getRole().name().equals("ADMIN") && !user.getRole().name().equals("HR")
            && !user.getEmployeeId().equals(employeeId))
            throw new org.springframework.security.access.AccessDeniedException("You can only update your own profile");
        return service.update(employeeId, request);
    }

    @DeleteMapping("/{employeeId}")
    @PreAuthorize("hasAnyRole('ADMIN','HR')")
    public void delete(@PathVariable String employeeId) { service.delete(employeeId); }

    @GetMapping("/suggest")
    public List<String> suggest(@RequestParam String q) { return autocomplete.suggest(q); }
}
