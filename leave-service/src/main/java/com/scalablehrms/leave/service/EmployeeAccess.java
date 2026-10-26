package com.scalablehrms.leave.service;

import com.scalablehrms.leave.dto.EmployeeSummary;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EmployeeAccess {
    private final EmployeeDirectoryClient directory;
    public EmployeeAccess(EmployeeDirectoryClient directory) { this.directory = directory; }
    public boolean hr(Jwt actor) {
        var roles = actor.getClaimAsStringList("roles");
        return roles != null && (roles.contains("HR") || roles.contains("ADMIN"));
    }
    public void active(Jwt actor, String bearer) { directory.requireActive(actor.getSubject(), bearer); }
    public boolean manages(Jwt actor, EmployeeSummary employee) {
        var roles = actor.getClaimAsStringList("roles");
        return hr(actor) || (roles != null && roles.contains("MANAGER") && actor.getSubject().equals(employee.managerId()));
    }
    public void review(Jwt actor, String bearer, String employeeId) {
        if (actor.getSubject().equals(employeeId))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot decide your own leave");
        active(actor, bearer);
        var target = directory.requireActive(employeeId, bearer);
        if (!manages(actor, target)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Employee is outside your approval scope");
    }
    public void history(Jwt actor, String bearer, String employeeId) {
        active(actor, bearer);
        if (!hr(actor) && !manages(actor, directory.requireActive(employeeId, bearer)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Employee is outside your approval scope");
    }
}
