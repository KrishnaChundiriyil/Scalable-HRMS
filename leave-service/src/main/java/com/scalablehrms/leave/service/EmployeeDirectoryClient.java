package com.scalablehrms.leave.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scalablehrms.leave.dto.EmployeeSummary;
import com.scalablehrms.leave.dto.EmployeeProfileResult;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EmployeeDirectoryClient {
    private static final Logger log = LoggerFactory.getLogger(EmployeeDirectoryClient.class);
    private final RestClient http;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final CircuitBreaker breaker;
    private final Retry retry;

    public EmployeeDirectoryClient(RestClient.Builder builder, StringRedisTemplate redis,
            ObjectMapper mapper, CircuitBreakerRegistry registry,
            @Value("${hrms.employee-service.url}") String baseUrl,
            @Value("${hrms.employee-service.connect-timeout-ms:1000}") int connectTimeout,
            @Value("${hrms.employee-service.read-timeout-ms:2000}") int readTimeout,
            @Value("${hrms.employee-service.max-attempts:2}") int attempts) {
        if (connectTimeout < 1 || readTimeout < 1 || attempts < 1 || attempts > 3)
            throw new IllegalArgumentException("Invalid Employee Service timeout or attempt count");
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        this.http = builder.requestFactory(factory).baseUrl(baseUrl).build();
        this.redis = redis;
        this.mapper = mapper;
        this.breaker = registry.circuitBreaker("employeeDirectory");
        this.breaker.getEventPublisher().onStateTransition(event ->
            log.info("dependency=employee-service transition={}", event.getStateTransition()));
        this.retry = Retry.of("employeeDirectoryReads", RetryConfig.custom()
            .maxAttempts(attempts).waitDuration(Duration.ofMillis(100))
            .retryOnException(EmployeeDirectoryClient::transientFailure).build());
    }

    private static boolean transientFailure(Throwable error) {
        if (error instanceof ResourceAccessException) return true;
        if (error instanceof RestClientResponseException response) {
            int status = response.getStatusCode().value();
            return status == 502 || status == 503 || status == 504;
        }
        return false;
    }

    private EmployeeSummary fetch(String employeeId, String authorizationHeader) {
        return breaker.executeSupplier(() -> retry.executeSupplier(() -> {
            EmployeeSummary info = http.get().uri("/api/employees/{id}", employeeId)
                .header(HttpHeaders.AUTHORIZATION, authorizationHeader)
                .retrieve().body(EmployeeSummary.class);
            if (info == null || !employeeId.equals(info.employeeId()))
                throw new IllegalStateException("Employee Service returned an invalid identity");
            return info;
        }));
    }

    public EmployeeSummary authoritative(String employeeId,String bearer) {
        try { return fetch(employeeId,bearer); }
        catch (RuntimeException failure) { throw unavailableOrDenied(failure); }
    }
    public java.util.List<EmployeeSummary> directReports(String managerId,String bearer) {
        try {
            var reports = breaker.executeSupplier(() -> retry.executeSupplier(() -> http.get()
                .uri("/api/employees/{id}/direct-reports",managerId)
                .header(HttpHeaders.AUTHORIZATION,bearer).retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<java.util.List<EmployeeSummary>>() {})));
            if (reports == null || reports.size()>10000 || reports.stream().anyMatch(e ->
                e.employeeId()==null || !managerId.equals(e.managerId())))
                throw new IllegalStateException("Invalid direct-report scope response");
            return reports.stream().filter(EmployeeSummary::active).toList();
        } catch (RuntimeException failure) { throw unavailableOrDenied(failure); }
    }

    /** Writes must always use the live directory and must never fall back to cache. */
    public EmployeeSummary requireActive(String employeeId, String authorizationHeader) {
        EmployeeSummary info;
        try {
            info = fetch(employeeId, authorizationHeader);
        } catch (RuntimeException failure) {
            throw unavailableOrDenied(failure);
        }
        if (!info.active()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Employee is inactive");
        return info;
    }

    public EmployeeProfileResult profile(String employeeId, String authorizationHeader) {
        try {
            EmployeeSummary info = fetch(employeeId, authorizationHeader);
            try {
                redis.opsForValue().set("hrms:profile:" + employeeId,
                    mapper.writeValueAsString(info), Duration.ofMinutes(5));
            } catch (Exception failure) {
                log.warn("dependency=redis operation=profile-cache-write outcome=failed");
            }
            return new EmployeeProfileResult(info, "EMPLOYEE_SERVICE", false);
        } catch (RuntimeException failure) {
            if (failure instanceof RestClientResponseException response && response.getStatusCode().is4xxClientError())
                throw unavailableOrDenied(failure);
            try {
                String json = redis.opsForValue().get("hrms:profile:" + employeeId);
                if (json != null) {
                    var cached = mapper.readValue(json, EmployeeSummary.class);
                    if (employeeId.equals(cached.employeeId()))
                        return new EmployeeProfileResult(cached, "CACHE", true);
                }
            } catch (Exception cacheFailure) {
                log.warn("dependency=redis operation=profile-cache-read outcome=failed");
            }
            throw unavailableOrDenied(failure);
        }
    }

    private ResponseStatusException unavailableOrDenied(RuntimeException failure) {
        if (failure instanceof RestClientResponseException response) {
            int status = response.getStatusCode().value();
            if (status == 401 || status == 403 || status == 404)
                return new ResponseStatusException(response.getStatusCode(), "Employee directory denied or could not find employee");
        }
        log.warn("dependency=employee-service outcome=unavailable circuit={} cause={}",
            breaker.getState(), failure.getClass().getSimpleName());
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
            "Employee directory unavailable; authoritative validation required");
    }
}
