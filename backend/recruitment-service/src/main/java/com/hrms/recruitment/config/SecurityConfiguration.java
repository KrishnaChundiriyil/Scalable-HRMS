package com.hrms.recruitment.config;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import com.fasterxml.jackson.databind.ObjectMapper;

@Configuration
public class SecurityConfiguration {
    @Bean
    JwtDecoder jwtDecoder(@Value("${hrms.security.jwt-secret-base64:}") String secret,
                          @Value("${hrms.security.jwk-set-uri:}") String jwks,
                          @Value("${hrms.security.issuer}") String issuer,
                          @Value("${hrms.security.audience}") String audience) {
        NimbusJwtDecoder decoder;
        if (!jwks.isBlank()) {
            decoder=NimbusJwtDecoder.withJwkSetUri(jwks).build();
        } else {
            byte[] key=Base64.getDecoder().decode(secret);
            if (key.length<32) throw new IllegalArgumentException("HRMS JWT secret must contain at least 32 decoded bytes");
            decoder=NimbusJwtDecoder.withSecretKey(new SecretKeySpec(key,"HmacSHA256")).macAlgorithm(MacAlgorithm.HS256).build();
        }
        OAuth2TokenValidator<Jwt> claims=jwt -> jwt.getExpiresAt()!=null && jwt.getSubject()!=null
            && jwt.getSubject().matches("[A-Za-z0-9][A-Za-z0-9_.@-]{0,127}") && jwt.getAudience().contains(audience)
            ? OAuth2TokenValidatorResult.success()
            : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token","Required subject, expiry or audience is missing/invalid",null));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer),claims));
        return decoder;
    }

    @Bean
    FilterRegistrationBean<ActiveEmployeeFilter> disableServletRegistration(ActiveEmployeeFilter filter) {
        var registration=new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,ActiveEmployeeFilter active,ObjectMapper mapper) throws Exception {
        org.springframework.security.web.AuthenticationEntryPoint unauthorized=(request,response,error)->{
            response.setStatus(401);response.setHeader("WWW-Authenticate","Bearer");response.setContentType("application/json");
            mapper.writeValue(response.getWriter(),Map.of("error","Valid employee login required"));
        };
        org.springframework.security.web.access.AccessDeniedHandler forbidden=(request,response,error)->{
            response.setStatus(403);response.setContentType("application/json");
            mapper.writeValue(response.getWriter(),Map.of("error","Access denied"));
        };
        var converter=new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt->{
            List<String> roles=jwt.getClaimAsStringList("roles");
            return (roles==null?List.<String>of():roles).stream()
                .map(role->(GrantedAuthority)new SimpleGrantedAuthority("ROLE_"+role)).toList();
        });
        http.csrf(csrf->csrf.disable()).sessionManagement(sm->sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth->auth
                .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll()
                .requestMatchers(HttpMethod.GET,"/actuator/health","/api/jobs","/api/jobs/*","/s/*","/demo-jobs/**").permitAll()
                .requestMatchers(HttpMethod.POST,"/api/candidates","/api/applications").permitAll()
                .requestMatchers(HttpMethod.GET,"/api/applications/me").authenticated()
                .requestMatchers("/api/storage/**").hasRole("ADMIN")
                .requestMatchers("/api/jobs","/api/jobs/**","/api/candidates","/api/applications","/api/applications/**",
                    "/api/shorten","/api/crawler/**","/api/notifications").hasAnyRole("HR","ADMIN")
                .anyRequest().denyAll())
            .exceptionHandling(errors->errors.authenticationEntryPoint(unauthorized).accessDeniedHandler(forbidden))
            .oauth2ResourceServer(oauth->oauth.authenticationEntryPoint(unauthorized).accessDeniedHandler(forbidden)
                .jwt(jwt->jwt.jwtAuthenticationConverter(converter)))
            .addFilterAfter(active,AuthorizationFilter.class);
        return http.build();
    }
}
