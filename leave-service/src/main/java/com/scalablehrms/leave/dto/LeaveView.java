package com.scalablehrms.leave.dto;

import com.scalablehrms.leave.domain.LeaveEntry;
import com.scalablehrms.leave.domain.LeaveStatus;
import com.scalablehrms.leave.domain.LeaveType;
import java.time.Instant;
import java.time.LocalDate;

public record LeaveView(String id, String employeeId, LeaveType type, LocalDate startDate,
                        LocalDate endDate, int days, String reason, LeaveStatus status,
                        Instant createdAt, Instant decidedAt, String reviewedBy) {
    public static LeaveView of(String employeeId, LeaveType type, LeaveEntry e) {
        return new LeaveView(e.getId(), employeeId, type, e.getStartDate(), e.getEndDate(),
            e.getDays(), e.getReason(), e.getStatus(), e.getCreatedAt(), e.getDecidedAt(), e.getReviewedBy());
    }
}
