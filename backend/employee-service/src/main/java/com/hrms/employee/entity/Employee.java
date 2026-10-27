package com.hrms.employee.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("employees")
public class Employee {
    @Id private String id;
    @Indexed(unique=true) private String employeeId;
     private String name;
    @Indexed(unique=true) private String email;
     private String passwordHash;
     private String department;
     private String role;
    private String jobTitle;
    private String phone;
    private Boolean active = true;
    private String managerId;
    private boolean emailOnlyLeave;
    private String leaveRequestEmail = "leave@alder.example";
    public boolean isEmailOnlyLeave(){return emailOnlyLeave;}
    public void setEmailOnlyLeave(boolean value){emailOnlyLeave=value;}
    public String getLeaveRequestEmail(){return leaveRequestEmail;}
    public void setLeaveRequestEmail(String value){leaveRequestEmail=value;}
    public boolean isActive(){return !Boolean.FALSE.equals(active);}
    public void setActive(boolean value){active=value;}
    public String getManagerId(){return managerId;}
    public void setManagerId(String value){managerId=value;}


    public String getId(){return id;} public void setId(String id){this.id=id;}
    public String getEmployeeId(){return employeeId;} public void setEmployeeId(String v){employeeId=v;}
    public String getName(){return name;} public void setName(String v){name=v;}
    public String getEmail(){return email;} public void setEmail(String v){email=v;}
    public String getPasswordHash(){return passwordHash;} public void setPasswordHash(String v){passwordHash=v;}
    public String getDepartment(){return department;} public void setDepartment(String v){department=v;}
    public String getRole(){return role;} public void setRole(String v){role=v;}
    public String getJobTitle(){return jobTitle;} public void setJobTitle(String v){jobTitle=v;}
    public String getPhone(){return phone;} public void setPhone(String v){phone=v;}
}
