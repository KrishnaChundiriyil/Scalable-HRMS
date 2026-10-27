package com.hrms.employee.controller;
import com.hrms.employee.dto.*;
import com.hrms.employee.service.EmployeeService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@RestController @RequestMapping("/api/employees")
public class EmployeeController {
 private final EmployeeService service;
 public EmployeeController(EmployeeService service){this.service=service;}
 private String role(Authentication a){return a.getAuthorities().stream().findFirst().map(x->x.getAuthority().replace("ROLE_","")).orElse("");}
 private boolean manager(Authentication a){return Set.of("ADMIN","HR").contains(role(a));}
 private void requireSelfOrManager(String id,Authentication a){if(!manager(a)&&!id.equals(a.getName()))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"You can access only your own profile");}
 @PostMapping @ResponseStatus(HttpStatus.CREATED) public EmployeeResponse create(@Valid @RequestBody RegisterRequest request,Authentication a){if(!manager(a))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Only ADMIN or HR can create employee accounts");return service.register(request,true);}
 @GetMapping public List<EmployeeResponse> directory(@RequestParam(required=false) String name,@RequestParam(required=false) String department,Authentication a){return service.search(name,department);}
 @GetMapping("/{employeeId}") public EmployeeResponse get(@PathVariable String employeeId,Authentication a){requireSelfOrManager(employeeId,a);return service.get(employeeId);}
 @PutMapping("/{employeeId}") public EmployeeResponse update(@PathVariable String employeeId,@Valid @RequestBody ProfileUpdateRequest request,Authentication a){requireSelfOrManager(employeeId,a);return service.update(employeeId,request);}
 @DeleteMapping("/{employeeId}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@PathVariable String employeeId,Authentication a){if(!"ADMIN".equals(role(a)))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Only ADMIN can delete employees");service.delete(employeeId);}
 @GetMapping("/suggest") public Map<String,Object> suggest(@RequestParam(defaultValue="employee") String type,@RequestParam String prefix,Authentication a){if(!Set.of("employee","department").contains(type.toLowerCase(Locale.ROOT)))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"type must be employee or department");return Map.of("type",type,"prefix",prefix,"suggestions",service.suggest(type,prefix));}
}
