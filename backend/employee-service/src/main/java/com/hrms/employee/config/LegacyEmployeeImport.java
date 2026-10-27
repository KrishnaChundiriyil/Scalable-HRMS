package com.hrms.employee.config;

import com.hrms.employee.entity.Employee;
import com.hrms.employee.repository.EmployeeRepository;
import java.sql.DriverManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Explicit, restart-safe import. Existing Mongo accounts and the H2 file are preserved. */
@Component
@Order(0)
@ConditionalOnProperty(name="app.legacy-import.enabled",havingValue="true")
public class LegacyEmployeeImport implements CommandLineRunner {
    private final EmployeeRepository employees;
    @Value("${app.legacy-import.url}") private String url;
    @Value("${app.legacy-import.username}") private String username;
    @Value("${app.legacy-import.password}") private String password;
    public LegacyEmployeeImport(EmployeeRepository employees){this.employees=employees;}
    @Override public void run(String...args) throws Exception {
        try(var connection=DriverManager.getConnection(url,username,password);
            var statement=connection.createStatement();var rows=statement.executeQuery("SELECT * FROM EMPLOYEES")) {
            while(rows.next()) {
                String id=rows.getString("EMPLOYEE_ID"),email=rows.getString("EMAIL");
                var byId=employees.findByEmployeeId(id);var byEmail=employees.findByEmailIgnoreCase(email);
                if(byId.isPresent()||byEmail.isPresent()) {
                    if(byId.isEmpty()||byEmail.isEmpty()||!byId.get().getId().equals(byEmail.get().getId()))
                        throw new IllegalStateException("Legacy employee conflicts with an existing Mongo account: "+id);
                    continue;
                }
                var employee=new Employee();employee.setEmployeeId(id);employee.setEmail(email);
                employee.setName(rows.getString("NAME"));employee.setPasswordHash(rows.getString("PASSWORD_HASH"));
                employee.setDepartment(rows.getString("DEPARTMENT"));employee.setRole(rows.getString("ROLE"));
                employee.setJobTitle(rows.getString("JOB_TITLE"));employee.setPhone(rows.getString("PHONE"));
                employee.setManagerId(rows.getString("MANAGER_ID"));employee.setActive(rows.getObject("ACTIVE")==null||rows.getBoolean("ACTIVE"));
                employees.insert(employee);
            }
        }
    }
}
