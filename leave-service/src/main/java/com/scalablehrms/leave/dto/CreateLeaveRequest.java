package com.scalablehrms.leave.dto;

import java.time.LocalDate;
import com.scalablehrms.leave.domain.LeaveType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateLeaveRequest(
    @NotNull LeaveType type,
    @NotNull LocalDate startDate,
    @NotNull LocalDate endDate,
    @NotBlank @Size(min=5,max=500) String reason
) {}
