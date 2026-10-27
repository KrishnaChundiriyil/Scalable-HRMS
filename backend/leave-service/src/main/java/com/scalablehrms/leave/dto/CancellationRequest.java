package com.scalablehrms.leave.dto;

import jakarta.validation.constraints.*;

public record CancellationRequest(@NotBlank @Size(min=5,max=500) String reason) {}
