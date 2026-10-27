package com.hrms.employee.dto;
import com.hrms.employee.entity.Employee;
public record EmployeeResponse(String employeeId, String name, String email, String department, String role, String jobTitle, String phone) {
 public static EmployeeResponse from(Employee e){return new EmployeeResponse(e.getEmployeeId(),e.getName(),e.getEmail(),e.getDepartment(),e.getRole(),e.getJobTitle(),e.getPhone());}
}
