package com.scalablehrms.leave.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

public record LeaveRequest(String id, String type, LocalDate startDate, LocalDate endDate,
    double days, Map<String, Double> chargedDays, boolean paid, String reason, LeaveStatus status,
    Instant createdAt, Instant decidedAt, String reviewedBy, String rejectionReason,
    Instant cancelledAt, String cancellationReason, String eligibilityNote, String idempotencyKey,
    LeaveSession session, Map<String,Double> dailyCharges) {
    public LeaveRequest {
        if (session == null) session = LeaveSession.FULL_DAY;
        if (dailyCharges == null) dailyCharges = Map.of();
    }
    // Retains source compatibility for the original full-day importer and test fixtures.
    public LeaveRequest(String id, String type, LocalDate startDate, LocalDate endDate,
        double days, Map<String,? extends Number> chargedDays, boolean paid, String reason, LeaveStatus status,
        Instant createdAt, Instant decidedAt, String reviewedBy, String rejectionReason,
        Instant cancelledAt, String cancellationReason, String eligibilityNote, String idempotencyKey) {
        this(id,type,startDate,endDate,days,numberMap(chargedDays),paid,reason,status,createdAt,decidedAt,
            reviewedBy,rejectionReason,cancelledAt,cancellationReason,eligibilityNote,idempotencyKey,LeaveSession.FULL_DAY,Map.of());
    }
    private static Map<String,Double> numberMap(Map<String,? extends Number> values) {
        var result = new java.util.LinkedHashMap<String,Double>();
        values.forEach((key,value) -> result.put(key,value.doubleValue()));
        return result;
    }
    public LeaveRequest decided(boolean approve, String actor, String rejection, String eligibility) {
        return new LeaveRequest(id, type, startDate, endDate, days, chargedDays, paid, reason,
            approve ? LeaveStatus.APPROVED : LeaveStatus.REJECTED, createdAt, Instant.now(), actor,
            rejection, null, null, eligibility, idempotencyKey, session, dailyCharges);
    }
    public LeaveRequest cancelled(String why) {
        return new LeaveRequest(id, type, startDate, endDate, days, chargedDays, paid, reason,
            LeaveStatus.CANCELLED, createdAt, decidedAt, reviewedBy, rejectionReason,
            Instant.now(), why, eligibilityNote, idempotencyKey, session, dailyCharges);
    }
}
