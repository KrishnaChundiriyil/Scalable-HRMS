package com.scalablehrms.leave.dto;

import java.time.LocalDate;
import jakarta.validation.constraints.*;

public record CreateLeaveRequest(@NotBlank @Pattern(regexp="[A-Z][A-Z0-9_]{0,39}") String type,
    @NotNull LocalDate startDate, @NotNull LocalDate endDate,
    @NotBlank @Size(min=5,max=500) String reason) {}
