package com.hrms.employee.controller;

import com.hrms.employee.entity.Employee;
import com.hrms.employee.repository.EmployeeRepository;
import com.hrms.employee.service.EmployeeCacheService;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LeaveDirectoryControllerTest {
    private final EmployeeRepository repository=mock(EmployeeRepository.class);
    private final EmployeeCacheService cache=mock(EmployeeCacheService.class);
    private final LeaveDirectoryController controller=new LeaveDirectoryController(repository,cache);
    private Employee employee,manager,admin;
    private Employee add(String id,String role,String managerId) {
        var result=new Employee();result.setEmployeeId(id);result.setName(id);result.setRole(role);result.setManagerId(managerId);
        when(repository.findByEmployeeId(id)).thenReturn(Optional.of(result));return result;
    }
    private Authentication auth(String id,String role) {return new UsernamePasswordAuthenticationToken(id,null,List.of(new SimpleGrantedAuthority("ROLE_"+role)));}
    @BeforeEach void setup() {
        manager=add("MGR1","MANAGER",null);employee=add("EMP1","EMPLOYEE","MGR1");admin=add("ADMIN1","ADMIN",null);
        add("MGR2","MANAGER",null);
        when(repository.save(any())).thenAnswer(call->call.getArgument(0));
    }
    @Test void onlyAssignedManagerCanReadSummary() {
        assertEquals("EMP1",controller.get("EMP1",auth("MGR1","MANAGER")).employeeId());
        assertEquals(403,assertThrows(ResponseStatusException.class,()->controller.get("EMP1",auth("MGR2","MANAGER"))).getStatusCode().value());
    }
    @Test void reportsExcludeInactiveEmployees() {
        var inactive=add("EMP2","EMPLOYEE","MGR1");inactive.setActive(false);
        when(repository.findByManagerId("MGR1")).thenReturn(List.of(employee,inactive));
        assertEquals(List.of("EMP1"),controller.reports("MGR1",auth("MGR1","MANAGER")).stream().map(LeaveDirectoryController.Summary::employeeId).toList());
    }
    @Test void employeeCannotChooseManagerOrReactivateSelf() {
        assertThrows(ResponseStatusException.class,()->controller.settings("EMP1",new LeaveDirectoryController.LeaveSettings("MGR2",true),auth("EMP1","EMPLOYEE")));
        verify(repository,never()).save(any());
    }
    @Test void directoryCredentialCannotChangeSettings() {
        assertEquals("EMP1",controller.get("EMP1",auth("leave-directory","LEAVE_DIRECTORY")).employeeId());
        assertThrows(ResponseStatusException.class,()->controller.settings("EMP1",new LeaveDirectoryController.LeaveSettings(null,false),auth("leave-directory","LEAVE_DIRECTORY")));
    }
    @Test void adminUpdatesAuthoritativeFieldsAndEvictsProfile() {
        var result=controller.settings("EMP1",new LeaveDirectoryController.LeaveSettings("MGR2",false),auth("ADMIN1","ADMIN"));
        assertEquals("MGR2",result.managerId());assertFalse(result.active());verify(cache).evict("EMP1");
    }
    @Test void rejectsCircularAndNonReviewerAssignments() {
        manager.setManagerId("EMP1");
        assertThrows(IllegalArgumentException.class,()->controller.settings("EMP1",new LeaveDirectoryController.LeaveSettings("MGR1",null),auth("ADMIN1","ADMIN")));
        assertThrows(IllegalArgumentException.class,()->controller.settings("MGR2",new LeaveDirectoryController.LeaveSettings("EMP1",null),auth("ADMIN1","ADMIN")));
        verify(repository,never()).save(any());
    }
    @Test void inactiveAdminCannotAssign() {
        admin.setActive(false);
        assertThrows(ResponseStatusException.class,()->controller.settings("EMP1",new LeaveDirectoryController.LeaveSettings(null,true),auth("ADMIN1","ADMIN")));
    }
}
