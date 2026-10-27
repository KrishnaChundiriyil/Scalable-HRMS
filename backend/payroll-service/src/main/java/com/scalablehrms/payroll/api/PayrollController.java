package com.scalablehrms.payroll.api;

import com.scalablehrms.payroll.domain.PayrollRecord;
import com.scalablehrms.payroll.dto.PayrollCalculation;
import com.scalablehrms.payroll.dto.PayrollRequest;
import com.scalablehrms.payroll.service.PayrollService;
import com.scalablehrms.payroll.service.EmployeeDirectory;
import com.scalablehrms.payroll.service.LeaveSummaryClient;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/payroll")
public class PayrollController {
    private final PayrollService payrollService;
    private final EmployeeDirectory employees;
    private final LeaveSummaryClient leave;

    public PayrollController(PayrollService payrollService,EmployeeDirectory employees,LeaveSummaryClient leave) {
        this.payrollService = payrollService;
        this.employees=employees;this.leave=leave;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP", "service", "payroll-service");
    }

    @PostMapping("/calculate")
    public PayrollCalculation calculate(@Valid @RequestBody PayrollRequest request,@RequestHeader("Authorization") String bearer) {
        employees.requireActive(request.employeeId(),bearer);
        return payrollService.calculate(request);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PayrollRecord create(@Valid @RequestBody PayrollRequest request,@RequestHeader("Authorization") String bearer) {
        employees.requireActive(request.employeeId(),bearer);
        return payrollService.create(request);
    }

    @GetMapping("/{id}")
    public PayrollRecord getById(@PathVariable String id,@AuthenticationPrincipal Jwt actor) {
        var record=payrollService.getById(id);requireScope(actor,record.getEmployeeId());return record;
    }

    @GetMapping("/employee/{employeeId}")
    public List<PayrollRecord> history(@PathVariable String employeeId,@AuthenticationPrincipal Jwt actor) {
        requireScope(actor,employeeId);
        return payrollService.history(employeeId);
    }

    @GetMapping("/employee/{employeeId}/year/{year}/month/{month}")
    public PayrollRecord getForPeriod(@PathVariable String employeeId,
                                      @PathVariable int year,
                                      @PathVariable int month,@AuthenticationPrincipal Jwt actor) {
        requireScope(actor,employeeId);
        return payrollService.getForPeriod(employeeId, year, month);
    }

    @GetMapping("/employee/{employeeId}/leave-summary")
    public Map<String,Object> leaveSummary(@PathVariable String employeeId,@RequestParam int year,@RequestParam int month,
            @AuthenticationPrincipal Jwt actor,@RequestHeader("Authorization") String bearer) {
        requireScope(actor,employeeId);return leave.summary(employeeId,year,month,hr(actor),bearer);
    }

    private boolean hr(Jwt actor) {
        var roles=actor.getClaimAsStringList("roles");
        return roles!=null&&(roles.contains("HR")||roles.contains("ADMIN"));
    }
    private void requireScope(Jwt actor,String employeeId) {
        if(!employeeId.equals(actor.getSubject())&&!hr(actor))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Payroll is private to the employee and HR/Admin");
    }
}
