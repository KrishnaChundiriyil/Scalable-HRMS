package com.hrms.employee.dto;
public record AuthResponse(String token, String tokenType, long expiresInSeconds, EmployeeResponse employee) {}
