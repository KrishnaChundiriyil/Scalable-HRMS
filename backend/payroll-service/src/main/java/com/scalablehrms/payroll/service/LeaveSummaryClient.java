package com.scalablehrms.payroll.service;

import java.time.YearMonth;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.*;
import org.springframework.web.server.ResponseStatusException;

/** Read-only period report; HR decides deductions. No salary amounts are changed. */
@Service
public class LeaveSummaryClient {
    private final RestClient leave;
    public LeaveSummaryClient(RestClient.Builder builder,@Value("${hrms.leave-service.url}") String baseUrl) {
        var factory=new SimpleClientHttpRequestFactory();factory.setConnectTimeout(1000);factory.setReadTimeout(3000);
        leave=builder.baseUrl(baseUrl).requestFactory(factory).build();
    }
    public Map<String,Object> summary(String employeeId,int year,int month,boolean hr,String bearer) {
        if(year<2000||year>2100||month<1||month>12)throw new IllegalArgumentException("Invalid payroll year/month");
        var period=YearMonth.of(year,month);
        try {
            Map<String,Object> report=leave.get().uri(uri->{
                uri.path(hr?"/api/leaves/reports":"/api/leaves/me/reports")
                    .queryParam("from",period.atDay(1)).queryParam("to",period.atEndOfMonth());
                if(hr)uri.queryParam("employeeId",employeeId);
                return uri.build();
            }).header("Authorization",bearer).retrieve().body(new ParameterizedTypeReference<Map<String,Object>>(){});
            if(report==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Invalid Leave service response");
            return Map.of("employeeId",employeeId,"year",year,"month",month,"leaveReport",report,"deductionsAppliedAutomatically",false);
        } catch(RestClientResponseException error) {
            int code=error.getStatusCode().value();
            throw new ResponseStatusException(code==401?HttpStatus.UNAUTHORIZED:code==403?HttpStatus.FORBIDDEN:HttpStatus.SERVICE_UNAVAILABLE,
                "Leave report unavailable or denied");
        } catch(RestClientException error) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Leave service unavailable");
        }
    }
}
