package com.scalablehrms.payroll.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;

public record PayrollRequest(
        @NotBlank @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9_.@-]{0,127}") String employeeId,
        @Min(2000) @Max(2100) int year,
        @Min(1) @Max(12) int month,
        @NotNull @DecimalMin("0.00") BigDecimal basicSalary,
        @NotNull @DecimalMin("0.00") BigDecimal allowances,
        @NotNull @DecimalMin("0.00") BigDecimal deductions
) {}
