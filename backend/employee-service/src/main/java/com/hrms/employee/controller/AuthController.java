package com.hrms.employee.controller;
import com.hrms.employee.dto.*;
import com.hrms.employee.entity.Employee;
import com.hrms.employee.security.JwtService;
import com.hrms.employee.service.EmployeeService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController @RequestMapping("/api/auth")
public class AuthController {
 private final EmployeeService employees;private final JwtService jwt;
 public AuthController(EmployeeService employees,JwtService jwt){this.employees=employees;this.jwt=jwt;}
 @PostMapping("/register") @ResponseStatus(HttpStatus.CREATED) public EmployeeResponse register(@Valid @RequestBody RegisterRequest request){return employees.register(request,false);}
 @PostMapping("/candidate-register") @ResponseStatus(HttpStatus.CREATED) public EmployeeResponse candidate(@Valid @RequestBody RegisterRequest request){return employees.register(new RegisterRequest(request.name(),request.email(),request.password(),"Applicants",request.jobTitle(),request.phone(),"CANDIDATE"),true);}
 @PostMapping("/login") public AuthResponse login(@Valid @RequestBody LoginRequest request){Employee e=employees.authenticate(request.email(),request.password());return new AuthResponse(jwt.generate(e.getEmployeeId(),e.getEmail(),e.getRole()),"Bearer",jwt.getExpirationSeconds(),EmployeeResponse.from(e));}
 @GetMapping("/health") public Map<String,String> health(){return Map.of("status","UP","service","employee-identity");}
}
