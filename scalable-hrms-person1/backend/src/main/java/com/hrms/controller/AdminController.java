package com.hrms.controller;

import com.hrms.dto.RoleUpdateRequest;
import com.hrms.model.Role;
import com.hrms.model.UserAccount;
import com.hrms.repository.UserRepository;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {
    private final UserRepository users;
    public AdminController(UserRepository users) { this.users = users; }

    @PutMapping("/users/{email}/role")
    public Map<String, String> updateRole(@PathVariable String email, @Valid @RequestBody RoleUpdateRequest request) {
        UserAccount user = users.findByEmailIgnoreCase(email).orElseThrow(() -> new IllegalArgumentException("User not found"));
        user.setRole(request.role());
        users.save(user);
        return Map.of("email", user.getEmail(), "employeeId", user.getEmployeeId(), "role", user.getRole().name());
    }
}
