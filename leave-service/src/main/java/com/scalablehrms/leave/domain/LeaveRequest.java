package com.scalablehrms.leave.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

public record LeaveRequest(String id, String type, LocalDate startDate, LocalDate endDate,
    int days, Map<String, Integer> chargedDays, boolean paid, String reason, LeaveStatus status,
    Instant createdAt, Instant decidedAt, String reviewedBy, String rejectionReason,
    Instant cancelledAt, String cancellationReason, String eligibilityNote, String idempotencyKey) {
    public LeaveRequest decided(boolean approve, String actor, String rejection, String eligibility) {
        return new LeaveRequest(id, type, startDate, endDate, days, chargedDays, paid, reason,
            approve ? LeaveStatus.APPROVED : LeaveStatus.REJECTED, createdAt, Instant.now(), actor,
            rejection, null, null, eligibility, idempotencyKey);
    }
    public LeaveRequest cancelled(String why) {
        return new LeaveRequest(id, type, startDate, endDate, days, chargedDays, paid, reason,
            LeaveStatus.CANCELLED, createdAt, decidedAt, reviewedBy, rejectionReason,
            Instant.now(), why, eligibilityNote, idempotencyKey);
    }
}
