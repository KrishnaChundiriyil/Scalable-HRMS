package com.scalablehrms.leave.config;

import java.util.Base64;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

@Configuration
public class SecurityConfiguration {
    @Bean
    JwtDecoder jwtDecoder(@Value("${hrms.security.jwt-secret-base64:}") String secret,
                          @Value("${hrms.security.jwk-set-uri:}") String jwks,
                          @Value("${hrms.security.issuer}") String issuer,
                          @Value("${hrms.security.audience}") String audience) {
        NimbusJwtDecoder decoder;
        if (!jwks.isBlank()) {
            decoder = NimbusJwtDecoder.withJwkSetUri(jwks).build();
        } else {
            byte[] decoded = Base64.getDecoder().decode(secret);
            if (decoded.length < 32) throw new IllegalArgumentException("HRMS JWT secret must be at least 32 bytes");
            decoder = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(decoded, "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256).build();
        }
        OAuth2TokenValidator<Jwt> claims = jwt -> {
            boolean valid = jwt.getExpiresAt() != null && jwt.getSubject() != null
                && jwt.getSubject().matches("[A-Za-z0-9][A-Za-z0-9_.@-]{0,127}")
                && jwt.getAudience().contains(audience);
            return valid ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(
                new OAuth2Error("invalid_token", "Required subject, expiry or audience is missing/invalid", null));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(issuer), claims));
        return decoder;
    }

    // Prevent Spring Boot from also registering the filter outside Spring Security,
    // where it could execute before the JWT has been authenticated.
    @Bean
    FilterRegistrationBean<RateLimitFilter> disableServletFilterRegistration(RateLimitFilter filter) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, RateLimitFilter limiter,
                                            com.fasterxml.jackson.databind.ObjectMapper mapper) throws Exception {
        org.springframework.security.web.AuthenticationEntryPoint unauthenticated = (request, response, error) -> {
            response.setStatus(401);
            response.setHeader("WWW-Authenticate", "Bearer");
            response.setContentType("application/json");
            mapper.writeValue(response.getWriter(), java.util.Map.of("error", "Valid authentication required"));
        };
        org.springframework.security.web.access.AccessDeniedHandler forbidden = (request, response, error) -> {
            response.setStatus(403);
            response.setContentType("application/json");
            mapper.writeValue(response.getWriter(), java.util.Map.of("error", "Access denied"));
        };
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            List<String> roles = jwt.getClaimAsStringList("roles");
            if (roles == null) roles = List.of();
            return roles.stream().map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
        });
        http.csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/leaves/pending", "/api/leaves/employees/**", "/api/leaves/requests", "/api/leaves/calendar", "/api/leaves/reports")
                    .hasAnyRole("MANAGER", "HR", "ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/leaves/*/*/decision").hasAnyRole("MANAGER", "HR", "ADMIN")
                .requestMatchers("/api/dev/**").authenticated()
                .requestMatchers("/api/leaves", "/api/leaves/**").hasAnyRole("EMPLOYEE", "MANAGER", "HR", "ADMIN")
                .anyRequest().denyAll())
            .exceptionHandling(errors -> errors.authenticationEntryPoint(unauthenticated).accessDeniedHandler(forbidden))
            .oauth2ResourceServer(oauth -> oauth.authenticationEntryPoint(unauthenticated).accessDeniedHandler(forbidden)
                .jwt(jwt -> jwt.jwtAuthenticationConverter(converter)))
            .addFilterAfter(limiter, BearerTokenAuthenticationFilter.class);
        return http.build();
    }
}
