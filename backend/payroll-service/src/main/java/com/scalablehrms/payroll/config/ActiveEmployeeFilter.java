package com.scalablehrms.payroll.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scalablehrms.payroll.service.EmployeeDirectory;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Map;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

@Component
public class ActiveEmployeeFilter extends OncePerRequestFilter {
    private final EmployeeDirectory employees;
    private final ObjectMapper mapper;
    public ActiveEmployeeFilter(EmployeeDirectory employees,ObjectMapper mapper) { this.employees=employees;this.mapper=mapper; }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth!=null&&auth.getPrincipal() instanceof Jwt jwt) {
            try { employees.requireActive(jwt.getSubject(),"Bearer "+jwt.getTokenValue()); }
            catch(ResponseStatusException error) {
                response.setStatus(error.getStatusCode().value());response.setContentType("application/json");
                if(error.getStatusCode().value()==401)response.setHeader("WWW-Authenticate","Bearer");
                mapper.writeValue(response.getWriter(),Map.of("error",error.getReason()));return;
            }
        }
        chain.doFilter(request,response);
    }
}
