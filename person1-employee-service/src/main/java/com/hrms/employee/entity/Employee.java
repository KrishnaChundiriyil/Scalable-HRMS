package com.hrms.employee.entity;

import jakarta.persistence.*;

@Entity
@Table(name="employees", uniqueConstraints={@UniqueConstraint(columnNames="email"), @UniqueConstraint(columnNames="employee_id")})
public class Employee {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="employee_id", nullable=false, unique=true) private String employeeId;
    @Column(nullable=false) private String name;
    @Column(nullable=false, unique=true) private String email;
    @Column(name="password_hash", nullable=false) private String passwordHash;
    @Column(nullable=false) private String department;
    @Column(nullable=false) private String role;
    private String jobTitle;
    private String phone;
    private Boolean active = true;
    private String managerId;
    public boolean isActive(){return !Boolean.FALSE.equals(active);}
    public void setActive(boolean value){active=value;}
    public String getManagerId(){return managerId;}
    public void setManagerId(String value){managerId=value;}


    public Long getId(){return id;} public void setId(Long id){this.id=id;}
    public String getEmployeeId(){return employeeId;} public void setEmployeeId(String v){employeeId=v;}
    public String getName(){return name;} public void setName(String v){name=v;}
    public String getEmail(){return email;} public void setEmail(String v){email=v;}
    public String getPasswordHash(){return passwordHash;} public void setPasswordHash(String v){passwordHash=v;}
    public String getDepartment(){return department;} public void setDepartment(String v){department=v;}
    public String getRole(){return role;} public void setRole(String v){role=v;}
    public String getJobTitle(){return jobTitle;} public void setJobTitle(String v){jobTitle=v;}
    public String getPhone(){return phone;} public void setPhone(String v){phone=v;}
}
