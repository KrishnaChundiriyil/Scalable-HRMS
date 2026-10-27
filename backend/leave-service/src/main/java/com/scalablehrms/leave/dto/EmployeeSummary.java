package com.scalablehrms.leave.dto;

public record EmployeeSummary(String employeeId, String name, String department, boolean active, String managerId, boolean emailOnlyLeave, String leaveRequestEmail, String role) {
 public EmployeeSummary(String employeeId,String name,String department,boolean active,String managerId){this(employeeId,name,department,active,managerId,false,"leave@alder.example","EMPLOYEE");}
}
