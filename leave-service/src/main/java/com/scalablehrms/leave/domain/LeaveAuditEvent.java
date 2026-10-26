package com.scalablehrms.leave.domain;
import java.time.Instant;
public record LeaveAuditEvent(String id, String leaveId, String actorId, String action,
    LeaveStatus previousStatus, LeaveStatus newStatus, Instant occurredAt, String reason,long sequence) {}
