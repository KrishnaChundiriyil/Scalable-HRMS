package com.scalablehrms.leave.domain;

import java.util.ArrayList;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

/** All years/types share one employee document: overlap, balance and outbox change atomically. */
@Document("leave_accounts")
public record LeaveAccount(@Id String id, @Version Long version, List<LeaveRequest> requests,
                           List<HrNotification> outbox, java.util.Map<String,Integer> allowances) {
    public static LeaveAccount fresh(String employeeId) {
        return new LeaveAccount(employeeId, null, new ArrayList<>(), new ArrayList<>(), new java.util.HashMap<>());
    }
}
