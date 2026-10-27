package com.hrms.employee.controller;

import com.hrms.employee.entity.Employee;
import com.hrms.employee.repository.EmployeeRepository;
import com.hrms.employee.service.EmployeeCacheService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Additive integration API; original registration, profile DTOs and CRUD are unchanged. */
@RestController
@RequestMapping("/api/internal/employees")
public class LeaveDirectoryController {
    public record Summary(String employeeId,String name,String department,boolean active,String managerId,boolean emailOnlyLeave,String leaveRequestEmail,String role) {
        static Summary of(Employee employee){return new Summary(employee.getEmployeeId(),employee.getName(),employee.getDepartment(),employee.isActive(),employee.getManagerId(),employee.isEmailOnlyLeave(),employee.getLeaveRequestEmail(),employee.getRole());}
    }
    public record LeaveSettings(@Pattern(regexp="[A-Za-z0-9_.@-]{0,128}") String managerId,Boolean active,Boolean emailOnlyLeave,@jakarta.validation.constraints.Email String leaveRequestEmail) { public LeaveSettings(String managerId,Boolean active){this(managerId,active,null,null);} }
    private final EmployeeRepository employees;
    private final EmployeeCacheService cache;
    public LeaveDirectoryController(EmployeeRepository employees,EmployeeCacheService cache){this.employees=employees;this.cache=cache;}
    private Employee employee(String id){return employees.findByEmployeeId(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Employee not found"));}
    private boolean service(Authentication actor){return actor.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_LEAVE_DIRECTORY"));}
    private Employee actor(Authentication auth){return employee(auth.getName());}
    private boolean hr(Employee employee){return Set.of("ADMIN","HR").contains(employee.getRole());}
    private Employee activeActor(Authentication auth){var employee=actor(auth);if(!employee.isActive())throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Employee is inactive");return employee;}

    @GetMapping("/{id}")
    public Summary get(@PathVariable String id,Authentication auth){
        Employee target=employee(id); // Always read the authoritative store, not the display-profile cache.
        if(!service(auth)){
            Employee actor=activeActor(auth);
            boolean assigned="MANAGER".equals(actor.getRole())&&actor.getEmployeeId().equals(target.getManagerId());
            if(!hr(actor)&&!actor.getEmployeeId().equals(id)&&!assigned)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Employee outside your scope");
        }
        return Summary.of(target);
    }
    @GetMapping("/{id}/direct-reports")
    public List<Summary> reports(@PathVariable String id,Authentication auth){
        if(!service(auth)){
            var actor=activeActor(auth);
            if(!hr(actor)&&!("MANAGER".equals(actor.getRole())&&actor.getEmployeeId().equals(id)))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Manager scope denied");
        }
        employee(id);
        return employees.findByManagerId(id).stream().filter(Employee::isActive).map(Summary::of).toList();
    }
    @PutMapping("/{id}/leave-settings")
    public Summary settings(@PathVariable String id,@Valid @RequestBody LeaveSettings settings,Authentication auth){
        if(service(auth)||!hr(activeActor(auth)))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Only HR/Admin can assign manager or active status");
        if(settings.managerId()==null&&settings.active()==null&&settings.emailOnlyLeave()==null&&settings.leaveRequestEmail()==null)throw new IllegalArgumentException("Supply at least one employment setting");
        Employee target=employee(id);
        if(settings.managerId()!=null){
            String manager=settings.managerId().isBlank()?null:settings.managerId();
            if(manager!=null){
                Employee candidate=employee(manager);
                if(!candidate.isActive()||!("MANAGER".equals(candidate.getRole())||hr(candidate)))throw new IllegalArgumentException("Assigned reviewer must be an active manager, HR or administrator");
                Set<String> chain=new HashSet<>(Set.of(id));
                for(Employee current=candidate;current!=null;){
                    if(!chain.add(current.getEmployeeId()))throw new IllegalArgumentException("Manager assignment would create a cycle");
                    current=current.getManagerId()==null?null:employee(current.getManagerId());
                }
            }
            target.setManagerId(manager);
        }
        if(settings.active()!=null)target.setActive(settings.active());
        if(settings.emailOnlyLeave()!=null)target.setEmailOnlyLeave(settings.emailOnlyLeave());
        if(settings.leaveRequestEmail()!=null){if(settings.leaveRequestEmail().isBlank())throw new IllegalArgumentException("Leave request email is required");target.setLeaveRequestEmail(settings.leaveRequestEmail());}
        Employee saved=employees.save(target);cache.evict(id);return Summary.of(saved);
    }
}
