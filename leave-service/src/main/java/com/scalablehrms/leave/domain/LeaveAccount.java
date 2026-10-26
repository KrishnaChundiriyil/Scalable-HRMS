package com.scalablehrms.leave.domain;
import java.time.Instant;
import java.util.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("leave_accounts")
@org.springframework.data.mongodb.core.index.CompoundIndex(name="request_filter",def="{'requests.status':1,'requests.startDate':1,'requests.endDate':1}")
public record LeaveAccount(@Id String id, @Version Long version, List<LeaveRequest> requests,
    List<HrNotification> outbox, Map<String,Integer> allowances, List<LeaveAuditEvent> auditTrail,
    Map<String,Instant> reminderAt) {
    public LeaveAccount {
        if (requests == null) requests = new ArrayList<>();
        if (outbox == null) outbox = new ArrayList<>();
        if (allowances == null) allowances = new HashMap<>();
        if (auditTrail == null) auditTrail = new ArrayList<>();
        if (reminderAt == null) reminderAt = new HashMap<>();
    }
    public static LeaveAccount fresh(String employeeId) {
        return new LeaveAccount(employeeId,null,new ArrayList<>(),new ArrayList<>(),new HashMap<>(),new ArrayList<>(),new HashMap<>());
    }
}
