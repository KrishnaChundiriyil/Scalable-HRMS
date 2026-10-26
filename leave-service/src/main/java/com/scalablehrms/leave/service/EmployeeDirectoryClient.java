package com.scalablehrms.leave.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scalablehrms.leave.dto.EmployeeSummary;
import com.scalablehrms.leave.dto.EmployeeProfileResult;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EmployeeDirectoryClient {
    private final RestClient http;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public EmployeeDirectoryClient(RestClient.Builder builder, StringRedisTemplate redis,
         ObjectMapper mapper, @Value("${hrms.employee-service.url}") String baseUrl) {
        this.http = builder.baseUrl(baseUrl).build();
        this.redis = redis;
        this.mapper = mapper;
    }

    // Only for read-only UI metadata; NEVER accept a cached response as employee validation for writes.
    @CircuitBreaker(name = "employeeDirectory", fallbackMethod = "cachedOrUnavailable")
    public EmployeeProfileResult profile(String employeeId, String authorizationHeader) {
        EmployeeSummary info = http.get().uri("/api/employees/{id}", employeeId)
            .header(HttpHeaders.AUTHORIZATION, authorizationHeader)
            .retrieve().body(EmployeeSummary.class);
        if (info == null) throw new IllegalStateException("Employee Service returned no profile");
        try {
            redis.opsForValue().set("hrms:profile:" + employeeId,
                mapper.writeValueAsString(info), Duration.ofMinutes(5));
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Employee profile cache write failed", e);
        }
        return new EmployeeProfileResult(info, "EMPLOYEE_SERVICE", false);
    }

    public EmployeeProfileResult cachedOrUnavailable(String employeeId, String authorizationHeader, Throwable cause) {
        if (cause instanceof RestClientResponseException upstream) {
            int status = upstream.getStatusCode().value();
            if (status == 401 || status == 403 || status == 404) {
                throw new ResponseStatusException(upstream.getStatusCode(),
                    "Employee directory denied or could not find employee", cause);
            }
        }
        try {
            String json = redis.opsForValue().get("hrms:profile:" + employeeId);
            if (json != null) return new EmployeeProfileResult(mapper.readValue(json, EmployeeSummary.class), "CACHE", true);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Profile fallback cache lookup failed", e);
        }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
            "Employee directory unavailable; no cached profile", cause);
    }
}
