package com.scalablehrms.leave.api;

import com.scalablehrms.leave.domain.*;
import com.scalablehrms.leave.dto.*;
import com.scalablehrms.leave.service.*;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/leaves")
public class LeaveFeaturesController {
    private final LeaveService leaves;
    private final LeaveQueryService queries;
    private final LeaveInsightsService insights;
    private final EmployeeAccess access;
    private final NotificationService notifications;
    public LeaveFeaturesController(LeaveService leaves,LeaveQueryService queries,LeaveInsightsService insights,
            EmployeeAccess access,NotificationService notifications) {
        this.leaves=leaves; this.queries=queries; this.insights=insights; this.access=access; this.notifications=notifications;
    }
    @PostMapping("/preview")
    public Map<String,Object> preview(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody LeavePreviewRequest input) {
        return leaves.preview(jwt.getSubject(),input.type(),input.startDate(),input.endDate(),input.session());
    }
    @GetMapping("/me/requests")
    public PageResult<LeaveView> mine(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required=false) String type,@RequestParam(required=false) LeaveStatus status,
            @RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to,
            @RequestParam(required=false) String search,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return queries.requests(Set.of(jwt.getSubject()),new LeaveSearch(null,type,status,from,to,search,page,size));
    }
    @GetMapping("/requests")
    public PageResult<LeaveView> search(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Authorization") String bearer,
            @RequestParam(required=false) String employeeId,@RequestParam(required=false) String type,
            @RequestParam(required=false) LeaveStatus status,@RequestParam(required=false) LocalDate from,
            @RequestParam(required=false) LocalDate to,@RequestParam(required=false) String search,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        var scope=access.scope(jwt,bearer); requireScope(scope,employeeId);
        return queries.requests(scope,new LeaveSearch(employeeId,type,status,from,to,search,page,size));
    }
    @GetMapping("/me/audit")
    public PageResult<LeaveAuditEvent> ownAudit(@AuthenticationPrincipal Jwt jwt,@RequestParam(required=false) String leaveId,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return queries.audit(jwt.getSubject(),leaveId,page,size);
    }
    @GetMapping("/employees/{employeeId}/audit")
    public PageResult<LeaveAuditEvent> employeeAudit(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Authorization") String bearer,
            @PathVariable String employeeId,@RequestParam(required=false) String leaveId,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        access.history(jwt,bearer,employeeId);
        return queries.audit(employeeId,leaveId,page,size);
    }
    @GetMapping("/calendar")
    public PageResult<LeaveInsightsService.CalendarAbsence> calendar(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Authorization") String bearer,@RequestParam LocalDate from,@RequestParam LocalDate to,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return insights.calendar(access.scope(jwt,bearer),bearer,from,to,page,size);
    }
    @GetMapping("/me/reports")
    public Map<String,Object> ownReport(@AuthenticationPrincipal Jwt jwt,@RequestParam LocalDate from,@RequestParam LocalDate to) {
        return insights.report(Set.of(jwt.getSubject()),null,from,to);
    }
    @GetMapping("/reports")
    public Map<String,Object> report(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Authorization") String bearer,
            @RequestParam(required=false) String employeeId,@RequestParam LocalDate from,@RequestParam LocalDate to) {
        var scope=access.scope(jwt,bearer); requireScope(scope,employeeId);
        return insights.report(scope,employeeId,from,to);
    }
    @GetMapping("/notifications/unread-count")
    public Map<String,Long> unread(@AuthenticationPrincipal Jwt jwt) { return Map.of("unread",notifications.unreadCount(jwt.getSubject())); }
    @PutMapping("/notifications/{id}/read")
    public ResponseEntity<Void> read(@AuthenticationPrincipal Jwt jwt,@PathVariable String id) {
        notifications.markRead(jwt.getSubject(),id); return ResponseEntity.noContent().build();
    }
    @PutMapping("/notifications/read-all")
    public Map<String,Long> readAll(@AuthenticationPrincipal Jwt jwt) { return Map.of("updated",notifications.markAllRead(jwt.getSubject())); }
    private void requireScope(Set<String> scope,String employee) {
        if (employee!=null && scope!=null && !scope.contains(employee))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Employee is outside your approval scope");
    }
}
