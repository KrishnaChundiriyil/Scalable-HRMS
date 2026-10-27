package com.scalablehrms.leave.dto;

public record EmployeeProfileResult(EmployeeSummary employee, String source, boolean stale) {}
