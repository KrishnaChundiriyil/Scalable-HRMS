package com.scalablehrms.payroll.api;

import com.scalablehrms.payroll.service.PayrollNotFoundException;
import com.scalablehrms.payroll.service.PayrollConflictException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ApiErrorHandler {
    @ExceptionHandler({PayrollConflictException.class,org.springframework.dao.DuplicateKeyException.class})
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String,String> conflict(RuntimeException ex) {
        return Map.of("error","Payroll already exists for this employee and month.");
    }

    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public org.springframework.http.ResponseEntity<Map<String,String>> dependency(org.springframework.web.server.ResponseStatusException ex) {
        return org.springframework.http.ResponseEntity.status(ex.getStatusCode())
            .body(Map.of("error",ex.getReason()==null?"Request denied":ex.getReason()));
    }
    @ExceptionHandler(PayrollNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> notFound(RuntimeException ex) {
        return Map.of("error", ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> badRequest(IllegalArgumentException ex) {
        return Map.of("error", ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> invalidRequest(MethodArgumentNotValidException ex) {
        return Map.of("error", "Invalid request. Check employeeId, year, month, and non-negative salary amounts.");
    }
}
