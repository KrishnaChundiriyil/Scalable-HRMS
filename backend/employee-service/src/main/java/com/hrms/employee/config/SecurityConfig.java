package com.hrms.employee.config;

import com.hrms.employee.security.JwtAuthFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import java.util.List;

@Configuration
public class SecurityConfig {
 @Bean PasswordEncoder passwordEncoder(){return new BCryptPasswordEncoder();}
 @Bean SecurityFilterChain filterChain(HttpSecurity http, JwtAuthFilter jwtAuthFilter) throws Exception {
  return http.csrf(csrf->csrf.disable()).cors(cors->cors.configurationSource(corsConfigurationSource()))
   .headers(h->h.frameOptions(f->f.sameOrigin())).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
   .authorizeHttpRequests(a->a.requestMatchers(org.springframework.http.HttpMethod.GET,"/api/company").permitAll().requestMatchers(org.springframework.http.HttpMethod.PUT,"/api/company").hasAnyRole("ADMIN","HR").requestMatchers("/api/auth/**","/h2-console/**","/error").permitAll().requestMatchers("/api/employees","/api/employees/suggest").hasAnyRole("ADMIN","HR","MANAGER","EMPLOYEE").requestMatchers("/api/employee-activity/me").hasAnyRole("ADMIN","HR","MANAGER","EMPLOYEE","CANDIDATE").anyRequest().authenticated())
   .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class).build();
 }
 @Bean CorsConfigurationSource corsConfigurationSource(){CorsConfiguration c=new CorsConfiguration();c.setAllowedOriginPatterns(List.of("*"));c.setAllowedMethods(List.of("GET","POST","PUT","PATCH","DELETE","OPTIONS"));c.setAllowedHeaders(List.of("*"));c.setAllowCredentials(false);UrlBasedCorsConfigurationSource s=new UrlBasedCorsConfigurationSource();s.registerCorsConfiguration("/**",c);return s;}
}
