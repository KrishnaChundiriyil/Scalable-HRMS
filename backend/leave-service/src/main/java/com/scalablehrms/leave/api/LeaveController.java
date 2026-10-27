package com.scalablehrms.leave.api;

import com.scalablehrms.leave.dto.*;
import com.scalablehrms.leave.config.LeavePolicy;
import com.scalablehrms.leave.domain.HrNotification;
import com.scalablehrms.leave.service.*;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/leaves")
public class LeaveController {
    private final LeaveService leaves;
    private final NotificationService notifications;
    private final EmployeeDirectoryClient directory;
    private final EmployeeAccess access;
    private final LeavePolicy policy;

    public LeaveController(LeaveService leaves, NotificationService notifications,
            EmployeeDirectoryClient directory, EmployeeAccess access, LeavePolicy policy) {
        this.leaves = leaves;
        this.notifications = notifications;
        this.directory = directory;
        this.access = access;
        this.policy = policy;
    }
    @GetMapping("/types")
    public Object types() { return policy.getTypes(); }

    @PostMapping
    public ResponseEntity<LeaveView> create(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Authorization") String bearer,
            @RequestHeader(value="Idempotency-Key", required=false) String key,
            @Valid @RequestBody CreateLeaveRequest input) {
        var employee = directory.requireActive(jwt.getSubject(), bearer);
        if(employee.emailOnlyLeave()) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.FORBIDDEN,"This position requests leave by email to " + employee.leaveRequestEmail());
        return ResponseEntity.status(HttpStatus.CREATED).body(leaves.submit(jwt.getSubject(), input, key));
    }
    @GetMapping("/me")
    public List<LeaveView> mine(@AuthenticationPrincipal Jwt jwt, @RequestParam(required=false) Integer year) {
        return leaves.mine(jwt.getSubject(), year);
    }
    @GetMapping("/me/balance")
    public Map<String,Object> balance(@AuthenticationPrincipal Jwt jwt, @RequestParam(required=false) Integer year) {
        return leaves.balance(jwt.getSubject(), year == null ? policy.today().getYear() : year);
    }
    @GetMapping("/employees/{employeeId}")
    public List<LeaveView> employeeHistory(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Authorization") String bearer, @PathVariable String employeeId,
            @RequestParam(required=false) Integer year) {
        access.history(jwt, bearer, employeeId);
        return leaves.mine(employeeId, year);
    }
    @GetMapping("/employees/{employeeId}/approved")
    public List<LeaveView> approved(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Authorization") String bearer, @PathVariable String employeeId,
            @RequestParam LocalDate from, @RequestParam LocalDate to) {
        access.history(jwt, bearer, employeeId);
        return leaves.approved(employeeId, from, to);
    }
    @GetMapping("/pending")
    public List<LeaveView> pending(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Authorization") String bearer, @RequestParam(required=false) Integer year) {
        var scope = access.scope(jwt,bearer);
        var pending = leaves.pending(year == null ? policy.today().getYear() : year);
        return scope==null ? pending : pending.stream().filter(item -> scope.contains(item.employeeId())).toList();
    }
    @PutMapping("/{employeeId}/{leaveId}/decision")
    public LeaveView decision(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Authorization") String bearer,
            @PathVariable String employeeId, @PathVariable String leaveId, @Valid @RequestBody DecisionRequest input) {
        access.review(jwt, bearer, employeeId);
        return leaves.decide(employeeId, leaveId, input, jwt.getSubject());
    }
    @PostMapping("/me/{leaveId}/cancel")
    public LeaveView cancel(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Authorization") String bearer,
            @PathVariable String leaveId, @Valid @RequestBody CancellationRequest input) {
        access.active(jwt, bearer);
        return leaves.cancel(jwt.getSubject(), leaveId, input.reason());
    }
    @GetMapping("/notifications")
    public List<HrNotification> notifications(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required=false) String after, @RequestParam(required=false) String before,
            @RequestParam(defaultValue="50") int limit) {
        return notifications.history(jwt.getSubject(), after, before, limit);
    }
    @GetMapping(value="/notifications/stream", produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value="Last-Event-ID",required=false) String lastEventId) {
        return notifications.subscribe(jwt.getSubject(), lastEventId, jwt.getExpiresAt());
    }
    @GetMapping("/me/employee-profile")
    public EmployeeProfileResult profile(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Authorization") String bearer) {
        return directory.profile(jwt.getSubject(), bearer);
    }
}
