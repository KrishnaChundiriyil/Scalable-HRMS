package com.hrms.controller;

import com.hrms.dto.AuthResponse;
import com.hrms.dto.LoginRequest;
import com.hrms.dto.RegisterRequest;
import com.hrms.model.UserAccount;
import com.hrms.repository.UserRepository;
import com.hrms.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;
    private final UserRepository users;
    public AuthController(AuthService auth, UserRepository users) { this.auth = auth; this.users = users; }

    @PostMapping("/register")
    public AuthResponse register(@Valid @RequestBody RegisterRequest request) { return auth.register(request); }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) { return auth.login(request); }

    @GetMapping("/me")
    public Map<String, Object> me(Authentication authentication) {
        UserAccount u = users.findByEmailIgnoreCase(authentication.getName()).orElseThrow();
        return Map.of("email", u.getEmail(), "employeeId", u.getEmployeeId(), "role", u.getRole());
    }
}
