package com.hrms.service;

import com.hrms.dto.AuthResponse;
import com.hrms.dto.LoginRequest;
import com.hrms.dto.RegisterRequest;
import com.hrms.model.Employee;
import com.hrms.model.Role;
import com.hrms.model.UserAccount;
import com.hrms.repository.EmployeeRepository;
import com.hrms.repository.UserRepository;
import com.hrms.security.JwtService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    private final UserRepository users;
    private final EmployeeRepository employees;
    private final EmployeeIdGenerator ids;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final SessionCacheService sessions;

    public AuthService(UserRepository users, EmployeeRepository employees, EmployeeIdGenerator ids,
                       PasswordEncoder encoder, JwtService jwt, SessionCacheService sessions) {
        this.users = users; this.employees = employees; this.ids = ids; this.encoder = encoder; this.jwt = jwt; this.sessions = sessions;
    }

    public AuthResponse register(RegisterRequest request) {
        String email = request.email().trim().toLowerCase();
        if (users.existsByEmailIgnoreCase(email))
            throw new IllegalArgumentException("Email is already registered");
        Role role = users.count() == 0 ? Role.ADMIN : Role.EMPLOYEE;
        // If HR created the employee record first, registration links the login account to it.
        Employee existingEmployee = employees.findByEmailIgnoreCase(email).orElse(null);
        String employeeId = existingEmployee == null ? ids.nextId() : existingEmployee.getEmployeeId();
        UserAccount user = users.save(new UserAccount(email, encoder.encode(request.password()), employeeId, role));
        if (existingEmployee == null) {
            employees.save(new Employee(employeeId, request.fullName().trim(),
                blankDefault(request.department(), "General"), blankDefault(request.jobTitle(), "Employee"),
                email, "", ""));
        } else {
            existingEmployee.setFullName(request.fullName().trim());
            existingEmployee.setDepartment(blankDefault(request.department(), existingEmployee.getDepartment()));
            existingEmployee.setJobTitle(blankDefault(request.jobTitle(), existingEmployee.getJobTitle()));
            employees.save(existingEmployee);
        }
        sessions.cacheLogin(user);
        return new AuthResponse(jwt.generate(user), user.getEmail(), user.getEmployeeId(), user.getRole());
    }

    public AuthResponse login(LoginRequest request) {
        UserAccount user = users.findByEmailIgnoreCase(request.email().trim())
            .orElseThrow(() -> new IllegalArgumentException("Invalid email or password"));
        if (!encoder.matches(request.password(), user.getPasswordHash()))
            throw new IllegalArgumentException("Invalid email or password");
        sessions.cacheLogin(user);
        return new AuthResponse(jwt.generate(user), user.getEmail(), user.getEmployeeId(), user.getRole());
    }

    private String blankDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
