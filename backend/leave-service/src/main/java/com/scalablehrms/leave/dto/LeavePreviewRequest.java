package com.scalablehrms.leave.dto;
import com.scalablehrms.leave.domain.LeaveSession;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
public record LeavePreviewRequest(@NotBlank String type,@NotNull LocalDate startDate,@NotNull LocalDate endDate,LeaveSession session) {
    public LeavePreviewRequest { if (session==null) session=LeaveSession.FULL_DAY; }
}
