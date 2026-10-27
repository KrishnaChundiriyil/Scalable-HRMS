package com.hrms.dto;

import com.hrms.model.Role;
import jakarta.validation.constraints.NotNull;

public record RoleUpdateRequest(@NotNull Role role) {}
