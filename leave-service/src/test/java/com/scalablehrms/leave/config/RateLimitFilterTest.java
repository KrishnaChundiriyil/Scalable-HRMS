package com.scalablehrms.leave.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scalablehrms.leave.service.RedisRateLimiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RateLimitFilterTest {
    @AfterEach void clearIdentity() { SecurityContextHolder.clearContext(); }

    @Test void rejectedWriteIncludesRetryAfter() throws Exception {
        var limiter = mock(RedisRateLimiter.class);
        when(limiter.retryAfterSeconds("EMP1")).thenReturn(2L);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(new Object(), null, List.of()) {
                @Override public String getName() { return "EMP1"; }
            });
        var request = new MockHttpServletRequest("POST", "/api/leaves");
        var response = new MockHttpServletResponse();
        new RateLimitFilter(limiter, new ObjectMapper()).doFilter(request, response, new MockFilterChain());
        assertEquals(429, response.getStatus());
        assertEquals("2", response.getHeader("Retry-After"));
    }

    @Test void streamAndReadsDoNotConsumeTokens() throws Exception {
        var limiter = mock(RedisRateLimiter.class);
        var filter = new RateLimitFilter(limiter, new ObjectMapper());
        for (String path : List.of("/api/leaves/me", "/api/leaves/notifications/stream")) {
            filter.doFilter(new MockHttpServletRequest("GET", path),
                new MockHttpServletResponse(), new MockFilterChain());
        }
        verifyNoInteractions(limiter);
    }

    @Test void invalidBucketConfigurationIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RedisRateLimiter(null, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new RedisRateLimiter(null, 5, 0));
    }
}
