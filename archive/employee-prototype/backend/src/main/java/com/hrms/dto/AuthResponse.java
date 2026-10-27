package com.hrms.dto;

import com.hrms.model.Role;

public record AuthResponse(String token, String email, String employeeId, Role role) {}
