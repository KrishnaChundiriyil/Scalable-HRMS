package com.scalablehrms.leave.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scalablehrms.leave.service.RedisRateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RateLimitFilter extends OncePerRequestFilter {
    private final RedisRateLimiter limiter;
    private final ObjectMapper mapper;
    public RateLimitFilter(RedisRateLimiter limiter, ObjectMapper mapper) {
        this.limiter=limiter; this.mapper=mapper;
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        if (request.getRequestURI().equals("/api/leaves/preview")) return true;
        boolean mutation = request.getMethod().equals("POST") || request.getMethod().equals("PUT")
            || request.getMethod().equals("DELETE") || request.getMethod().equals("PATCH");
        return !mutation || !(request.getRequestURI().startsWith("/api/leaves/") ||
                                 request.getRequestURI().equals("/api/leaves") ||
                                 request.getRequestURI().startsWith("/api/dev/"));
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                               FilterChain chain) throws IOException, ServletException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getPrincipal() instanceof String) {
            chain.doFilter(request, response); return;
        }
        try {
            long retry = limiter.retryAfterSeconds(auth.getName());
            if (retry > 0) {
                response.setStatus(429);
                response.setHeader("Retry-After", Long.toString(retry));
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.getWriter().write(mapper.writeValueAsString(Map.of("error", "Too Many Requests")));
                return;
            }
        } catch (Exception failure) {
            response.setStatus(503);
            response.setHeader("Retry-After", "1");
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(mapper.writeValueAsString(Map.of("error", "Rate limiting unavailable")));
            return;
        }
        chain.doFilter(request,response);
    }
}
