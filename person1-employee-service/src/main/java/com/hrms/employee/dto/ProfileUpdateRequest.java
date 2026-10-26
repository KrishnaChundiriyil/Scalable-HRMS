package com.hrms.employee.dto;
import jakarta.validation.constraints.*;
public record ProfileUpdateRequest(@Size(min=2,max=100) String name, String department, String jobTitle, String phone) {}
