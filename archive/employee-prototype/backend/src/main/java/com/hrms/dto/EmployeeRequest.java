package com.hrms.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record EmployeeRequest(
    @NotBlank String fullName,
    @NotBlank String department,
    @NotBlank String jobTitle,
    @Email @NotBlank String email,
    String phone,
    String location
) {}
