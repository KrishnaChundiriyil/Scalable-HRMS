package com.hrms.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document("employees")
public class Employee implements java.io.Serializable {
    @Id
    private String id;
    @Indexed(unique = true)
    private String employeeId;
    private String fullName;
    @Indexed
    private String department;
    @Indexed
    private String jobTitle;
    @Indexed
    private String email;
    private String phone;
    private String location;
    private Instant createdAt = Instant.now();

    public Employee() {}
    public Employee(String employeeId, String fullName, String department, String jobTitle,
                    String email, String phone, String location) {
        this.employeeId = employeeId; this.fullName = fullName; this.department = department;
        this.jobTitle = jobTitle; this.email = email; this.phone = phone; this.location = location;
    }
    public String getId() { return id; }
    public String getEmployeeId() { return employeeId; }
    public void setEmployeeId(String employeeId) { this.employeeId = employeeId; }
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public String getDepartment() { return department; }
    public void setDepartment(String department) { this.department = department; }
    public String getJobTitle() { return jobTitle; }
    public void setJobTitle(String jobTitle) { this.jobTitle = jobTitle; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public Instant getCreatedAt() { return createdAt; }
}
