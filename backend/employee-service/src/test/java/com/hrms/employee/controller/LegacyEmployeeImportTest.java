package com.hrms.employee.controller;

import com.hrms.employee.config.LegacyEmployeeImport;
import com.hrms.employee.entity.Employee;
import com.hrms.employee.repository.EmployeeRepository;
import java.sql.DriverManager;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LegacyEmployeeImportTest {
    @Test void preservesIdentityCredentialsAndAccessAndCanRunTwice() throws Exception {
        String url="jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1";
        try(var connection=DriverManager.getConnection(url,"sa","");var sql=connection.createStatement()) {
            sql.execute("CREATE TABLE EMPLOYEES (EMPLOYEE_ID VARCHAR, EMAIL VARCHAR, NAME VARCHAR, PASSWORD_HASH VARCHAR, DEPARTMENT VARCHAR, ROLE VARCHAR, JOB_TITLE VARCHAR, PHONE VARCHAR, MANAGER_ID VARCHAR, ACTIVE BOOLEAN)");
            sql.execute("INSERT INTO EMPLOYEES VALUES ('EMP01001','legacy@example.test','Legacy Employee','existing-bcrypt-hash','Engineering','EMPLOYEE','Engineer','','MGR1',FALSE)");
        }
        var repository=mock(EmployeeRepository.class);var stored=new HashMap<String,Employee>();
        when(repository.findByEmployeeId(anyString())).thenAnswer(call->Optional.ofNullable(stored.get(call.getArgument(0))));
        when(repository.findByEmailIgnoreCase(anyString())).thenAnswer(call->stored.values().stream().filter(e->e.getEmail().equals(call.getArgument(0))).findFirst());
        when(repository.insert(any(Employee.class))).thenAnswer(call->{Employee employee=call.getArgument(0);employee.setId("mongo-id");stored.put(employee.getEmployeeId(),employee);return employee;});
        var importer=new LegacyEmployeeImport(repository);
        ReflectionTestUtils.setField(importer,"url",url);ReflectionTestUtils.setField(importer,"username","sa");ReflectionTestUtils.setField(importer,"password","");
        importer.run();importer.run();
        verify(repository,times(1)).insert(any(Employee.class));
        var migrated=stored.get("EMP01001");assertEquals("existing-bcrypt-hash",migrated.getPasswordHash());
        assertEquals("legacy@example.test",migrated.getEmail());assertEquals("MGR1",migrated.getManagerId());assertFalse(migrated.isActive());
    }
}
