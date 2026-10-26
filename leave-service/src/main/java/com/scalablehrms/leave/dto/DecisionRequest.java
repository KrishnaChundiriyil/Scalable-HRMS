package com.scalablehrms.leave.dto;

import jakarta.validation.constraints.NotNull;
public record DecisionRequest(@NotNull Boolean approve) {}
