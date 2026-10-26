package com.scalablehrms.payroll.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.*;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EmployeeDirectory {
    public record Summary(String employeeId,Boolean active) {}
    private final RestClient employees;
    private final String path;
    public EmployeeDirectory(RestClient.Builder builder,
            @Value("${hrms.employee-service.url}") String baseUrl,
            @Value("${hrms.employee-service.directory-path}") String path,
            @Value("${hrms.employee-service.connect-timeout-ms}") int connectTimeout,
            @Value("${hrms.employee-service.read-timeout-ms}") int readTimeout) {
        if(connectTimeout<1||readTimeout<1)throw new IllegalArgumentException("Employee timeouts must be positive");
        var factory=new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);factory.setReadTimeout(readTimeout);
        employees=builder.baseUrl(baseUrl).requestFactory(factory).build();this.path=path.replaceAll("/+$","");
    }
    public void requireActive(String employeeId,String bearer) {
        Summary info;
        try {
            info=employees.get().uri(path+"/{id}",employeeId).header("Authorization",bearer).retrieve().body(Summary.class);
        } catch(RestClientResponseException error) {
            int code=error.getStatusCode().value();
            throw new ResponseStatusException(code==401?HttpStatus.UNAUTHORIZED:code==403?HttpStatus.FORBIDDEN:
                code==404?HttpStatus.NOT_FOUND:HttpStatus.SERVICE_UNAVAILABLE,"Employee validation unavailable or denied");
        } catch(RestClientException error) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Employee service unavailable");
        }
        if(info==null||!employeeId.equals(info.employeeId()))
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Invalid Employee service response");
        if(!Boolean.TRUE.equals(info.active()))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Employee is inactive");
    }
}
