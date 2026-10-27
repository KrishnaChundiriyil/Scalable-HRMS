package com.scalablehrms.payroll.dto;

import java.math.BigDecimal;

public record PayrollCalculation(
        BigDecimal basicSalary,
        BigDecimal allowances,
        BigDecimal deductions,
        BigDecimal grossSalary,
        BigDecimal netSalary
) {}
