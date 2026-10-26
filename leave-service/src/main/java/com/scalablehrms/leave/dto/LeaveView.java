package com.scalablehrms.leave.dto;

import com.scalablehrms.leave.domain.*;
import java.time.*;
import java.util.Map;

public record LeaveView(String id, String employeeId, String type, LocalDate startDate,
    LocalDate endDate, int days, Map<String,Integer> chargedDays, boolean paid, String reason,
    LeaveStatus status, Instant createdAt, Instant decidedAt, String reviewedBy,
    String rejectionReason, Instant cancelledAt, String cancellationReason, String eligibilityNote) {
    public static LeaveView of(String employeeId, LeaveRequest request) {
        return new LeaveView(request.id(), employeeId, request.type(), request.startDate(), request.endDate(),
            request.days(), request.chargedDays(), request.paid(), request.reason(), request.status(),
            request.createdAt(), request.decidedAt(), request.reviewedBy(), request.rejectionReason(),
            request.cancelledAt(), request.cancellationReason(), request.eligibilityNote());
    }
}
