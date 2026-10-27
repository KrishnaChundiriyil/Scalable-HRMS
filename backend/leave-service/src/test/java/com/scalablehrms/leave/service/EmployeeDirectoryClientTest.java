package com.scalablehrms.leave.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EmployeeDirectoryClientTest {
    HttpServer upstream;
    AtomicInteger status = new AtomicInteger(503), calls = new AtomicInteger(), delay = new AtomicInteger();
    String json = "{\"employeeId\":\"EMP1\",\"active\":true,\"managerId\":\"MGR1\"}";
    StringRedisTemplate redis;
    ValueOperations<String,String> values;
    EmployeeDirectoryClient client;
    CircuitBreaker breaker;

    @BeforeEach @SuppressWarnings("unchecked") void setUp() throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/api/employees/EMP1", exchange -> {
            calls.incrementAndGet();
            try { Thread.sleep(delay.get()); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            byte[] bytes = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            try {
                exchange.sendResponseHeaders(status.get(), bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally { exchange.close(); }
        });
        upstream.start();
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        var config = CircuitBreakerConfig.custom().slidingWindowSize(2).minimumNumberOfCalls(2)
            .failureRateThreshold(50).permittedNumberOfCallsInHalfOpenState(1)
            .waitDurationInOpenState(Duration.ofMillis(100))
            .ignoreExceptions(HttpClientErrorException.class).build();
        var registry = CircuitBreakerRegistry.of(config);
        breaker = registry.circuitBreaker("employeeDirectory");
        client = new EmployeeDirectoryClient(RestClient.builder(), redis, new ObjectMapper(), registry,
            "http://127.0.0.1:" + upstream.getAddress().getPort(), 100, 100, 2, "/api/employees");
    }

    @AfterEach void stop() { upstream.stop(0); }

    @Test void breakerOpensSkipsCallsAndRecoversThroughHalfOpen() throws Exception {
        assertThrows(ResponseStatusException.class, () -> client.requireActive("EMP1", "Bearer test"));
        assertThrows(ResponseStatusException.class, () -> client.requireActive("EMP1", "Bearer test"));
        assertEquals(CircuitBreaker.State.OPEN, breaker.getState());
        int before = calls.get();
        assertThrows(ResponseStatusException.class, () -> client.requireActive("EMP1", "Bearer test"));
        assertEquals(before, calls.get());
        var transitions = new java.util.ArrayList<CircuitBreaker.StateTransition>();
        breaker.getEventPublisher().onStateTransition(e -> transitions.add(e.getStateTransition()));
        Thread.sleep(150);
        status.set(200);
        assertTrue(client.requireActive("EMP1", "Bearer test").active());
        assertEquals(CircuitBreaker.State.CLOSED, breaker.getState());
        assertTrue(transitions.contains(CircuitBreaker.StateTransition.OPEN_TO_HALF_OPEN));
        assertTrue(transitions.contains(CircuitBreaker.StateTransition.HALF_OPEN_TO_CLOSED));
    }

    @Test void cachedDataIsAllowedForReadsButNeverForWrites() {
        when(values.get("hrms:profile:EMP1")).thenReturn(json);
        var cached = client.profile("EMP1", "Bearer test");
        assertTrue(cached.stale());
        assertEquals("CACHE", cached.source());
        var failure = assertThrows(ResponseStatusException.class, () -> client.requireActive("EMP1", "Bearer test"));
        assertEquals(503, failure.getStatusCode().value());
    }

    @Test void deniedProfileDoesNotUseCacheOrRetry() {
        status.set(403);
        when(values.get("hrms:profile:EMP1")).thenReturn(json);
        var failure = assertThrows(ResponseStatusException.class, () -> client.profile("EMP1", "Bearer test"));
        assertEquals(403, failure.getStatusCode().value());
        assertEquals(1, calls.get());
        verify(values, never()).get(anyString());
    }

    @Test void inactiveEmployeeCannotPassWriteValidation() {
        status.set(200);
        json = "{\"employeeId\":\"EMP1\",\"active\":false}";
        var failure = assertThrows(ResponseStatusException.class, () -> client.requireActive("EMP1", "Bearer test"));
        assertEquals(403, failure.getStatusCode().value());
    }

    @Test void mismatchedEmployeeIdentityFailsSafely() {
        status.set(200);
        json = "{\"employeeId\":\"SOMEONE_ELSE\",\"active\":true}";
        var failure = assertThrows(ResponseStatusException.class, () -> client.requireActive("EMP1", "Bearer test"));
        assertEquals(503, failure.getStatusCode().value());
    }

    @Test void upstreamReadTimeoutIsBounded() {
        delay.set(500);
        assertTimeout(Duration.ofSeconds(2), () -> {
            var failure = assertThrows(ResponseStatusException.class, () -> client.requireActive("EMP1", "Bearer test"));
            assertEquals(503, failure.getStatusCode().value());
        });
    }
}
