package com.hrms.recruitment.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import org.springframework.web.filter.OncePerRequestFilter;

/** Staff operations require a live active employee; cached profiles never grant access. */
@Component
public class ActiveEmployeeFilter extends OncePerRequestFilter {
    public record EmployeeSummary(String employeeId,Boolean active) {}
    private final RestClient employees;
    private final ObjectMapper mapper;
    private final String directory;

    public ActiveEmployeeFilter(RestClient.Builder builder,ObjectMapper mapper,
            @Value("${hrms.employee-service.url}") String baseUrl,
            @Value("${hrms.employee-service.directory-path:/api/internal/employees}") String directory,
            @Value("${hrms.employee-service.connect-timeout-ms:1000}") int connectTimeout,
            @Value("${hrms.employee-service.read-timeout-ms:2000}") int readTimeout) {
        if(connectTimeout<1||readTimeout<1)throw new IllegalArgumentException("Employee timeouts must be positive");
        var factory=new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);factory.setReadTimeout(readTimeout);
        employees=builder.baseUrl(baseUrl).requestFactory(factory).build();
        this.mapper=mapper;this.directory=directory.replaceAll("/+$","");
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth!=null&&auth.getPrincipal() instanceof Jwt jwt) {
            EmployeeSummary employee;
            try {
                employee=employees.get().uri(directory+"/{id}",jwt.getSubject())
                    .header(HttpHeaders.AUTHORIZATION,"Bearer "+jwt.getTokenValue()).retrieve().body(EmployeeSummary.class);
            } catch(RestClientResponseException error) {
                int code=error.getStatusCode().value();
                fail(response,code==401?401:(code==403||code==404?403:503),"Employee authorization unavailable or denied");return;
            } catch(RestClientException error) {
                fail(response,503,"Employee service unavailable");return;
            }
            if(employee==null||!jwt.getSubject().equals(employee.employeeId())) {
                fail(response,503,"Invalid Employee service response");return;
            }
            if(!Boolean.TRUE.equals(employee.active())) {
                fail(response,403,"Employee is inactive");return;
            }
        }
        chain.doFilter(request,response);
    }
    private void fail(HttpServletResponse response,int status,String message)throws IOException {
        response.setStatus(status);response.setContentType("application/json");
        if(status==401)response.setHeader("WWW-Authenticate","Bearer");
        mapper.writeValue(response.getWriter(),Map.of("error",message));
    }
}
