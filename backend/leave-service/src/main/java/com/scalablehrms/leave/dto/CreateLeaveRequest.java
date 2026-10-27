package com.scalablehrms.leave.dto;

import java.time.LocalDate;
import jakarta.validation.constraints.*;

public record CreateLeaveRequest(@NotBlank @Pattern(regexp="[A-Z][A-Z0-9_]{0,39}") String type,
    @NotNull LocalDate startDate, @NotNull LocalDate endDate,
    @NotBlank @Size(min=5,max=500) String reason, com.scalablehrms.leave.domain.LeaveSession session) {
    public CreateLeaveRequest {
        if (session == null) session = com.scalablehrms.leave.domain.LeaveSession.FULL_DAY;
    }
    public CreateLeaveRequest(String type, LocalDate startDate, LocalDate endDate, String reason) {
        this(type,startDate,endDate,reason,com.scalablehrms.leave.domain.LeaveSession.FULL_DAY);
    }
}
