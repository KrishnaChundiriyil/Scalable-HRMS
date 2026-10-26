package com.scalablehrms.leave.api;

import com.scalablehrms.leave.dto.*;
import com.scalablehrms.leave.domain.HrNotification;
import com.scalablehrms.leave.service.*;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/leaves")
public class LeaveController {
    private final LeaveService leaves;
    private final NotificationService notifications;
    private final EmployeeDirectoryClient directory;

    public LeaveController(LeaveService leaves, NotificationService notifications,
                           EmployeeDirectoryClient directory) {
        this.leaves=leaves; this.notifications=notifications; this.directory=directory;
    }

    @PostMapping
    public ResponseEntity<LeaveView> create(@AuthenticationPrincipal Jwt jwt,
                                            @Valid @RequestBody CreateLeaveRequest input) {
        return ResponseEntity.status(HttpStatus.CREATED).body(leaves.submit(jwt.getSubject(), input));
    }
    @GetMapping("/me")
    public List<LeaveView> mine(@AuthenticationPrincipal Jwt jwt, @RequestParam(required=false) Integer year) {
        return leaves.mine(jwt.getSubject(), year);
    }
    @GetMapping("/me/balance")
    public Map<String,Object> balance(@AuthenticationPrincipal Jwt jwt,
                                      @RequestParam(required=false) Integer year) {
        return leaves.balance(jwt.getSubject(), year == null ? LocalDate.now().getYear() : year);
    }
    @GetMapping("/employees/{employeeId}")
    public List<LeaveView> employeeHistory(@PathVariable String employeeId,
                                            @RequestParam(required=false) Integer year) {
        return leaves.mine(employeeId, year);
    }
    @GetMapping("/pending")
    public List<LeaveView> pending(@RequestParam(required=false) Integer year) {
        return leaves.pending(year == null ? LocalDate.now().getYear() : year);
    }
    @PutMapping("/{employeeId}/{leaveId}/decision")
    public LeaveView decision(@AuthenticationPrincipal Jwt jwt, @PathVariable String employeeId,
                              @PathVariable String leaveId, @Valid @RequestBody DecisionRequest input) {
        if (jwt.getSubject().equals(employeeId)) throw new ResponseStatusException(HttpStatus.FORBIDDEN,
            "You cannot approve your own leave");
        return leaves.decide(employeeId, leaveId, input.approve(), jwt.getSubject());
    }
    @GetMapping("/notifications")
    public List<HrNotification> notifications(@AuthenticationPrincipal Jwt jwt) {
        return notifications.recent(jwt.getSubject());
    }
    @GetMapping(value="/notifications/stream", produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@AuthenticationPrincipal Jwt jwt) {
        return notifications.subscribe(jwt.getSubject());
    }
    @GetMapping("/me/employee-profile")
    public EmployeeProfileResult myEmployeeProfile(@AuthenticationPrincipal Jwt jwt,
                       @RequestHeader("Authorization") String bearer) {
        return directory.profile(jwt.getSubject(), bearer);
    }
}
