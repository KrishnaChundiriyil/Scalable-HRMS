package com.scalablehrms.leave.dto;

import jakarta.validation.constraints.*;

public record DecisionRequest(@NotNull Boolean approve, @Size(max=500) String rejectionReason,
                              @Size(max=500) String eligibilityNote) {}
