package com.scalablehrms.leave.service;

import com.scalablehrms.leave.domain.*;
import com.scalablehrms.leave.dto.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class LeaveInsightsService {
    public record CalendarAbsence(String employeeId,String employeeName,LocalDate startDate,
                                  LocalDate endDate,LeaveSession session) {}
    private final LeaveQueryService queries;
    private final EmployeeDirectoryClient directory;
    public LeaveInsightsService(LeaveQueryService queries,EmployeeDirectoryClient directory) {
        this.queries=queries; this.directory=directory;
    }
    private void range(LocalDate from,LocalDate to) {
        if (from==null || to==null || to.isBefore(from) || to.isAfter(from.plusDays(365)))
            throw new IllegalArgumentException("Range must contain 1 to 366 days");
    }
    public PageResult<CalendarAbsence> calendar(Set<String> scope,String bearer,LocalDate from,LocalDate to,int page,int size) {
        range(from,to);
        var result=queries.requests(scope,new LeaveSearch(null,null,LeaveStatus.APPROVED,from,to,null,page,size));
        Map<String,String> names=new HashMap<>();
        var entries=result.items().stream().map(leave -> {
            String name=names.computeIfAbsent(leave.employeeId(),id -> directory.authoritative(id,bearer).name());
            return new CalendarAbsence(leave.employeeId(),name,
                leave.startDate().isBefore(from)?from:leave.startDate(),leave.endDate().isAfter(to)?to:leave.endDate(),leave.session());
        }).toList();
        return PageResult.of(entries,page,size,result.totalElements());
    }
    public Map<String,Object> report(Set<String> scope,String employeeId,LocalDate from,LocalDate to) {
        range(from,to);
        Map<String,Long> statuses=new LinkedHashMap<>();
        Map<String,Double> byType=new TreeMap<>();
        Map<String,Map<String,Double>> months=new TreeMap<>();
        for (YearMonth month=YearMonth.from(from); !month.isAfter(YearMonth.from(to)); month=month.plusMonths(1))
            months.put(month.toString(),new TreeMap<>());
        double paid=0,unpaid=0,decisionSeconds=0;
        long decisions=0,requests=0,legacy=0;
        for (int page=0;;page++) {
            var result=queries.requests(scope,new LeaveSearch(employeeId,null,null,from,to,null,page,100));
            for (LeaveView leave:result.items()) {
                requests++;
                statuses.merge(leave.status().name(),1L,Long::sum);
                if (leave.decidedAt()!=null) {
                    decisions++;
                    decisionSeconds+=Math.max(0,Duration.between(leave.createdAt(),leave.decidedAt()).toMillis()/1000.0);
                }
                if (leave.status()!=LeaveStatus.APPROVED) continue;
                if (leave.dailyCharges().isEmpty()) { legacy++; continue; }
                for (var day:leave.dailyCharges().entrySet()) {
                    LocalDate date=LocalDate.parse(day.getKey());
                    if (date.isBefore(from) || date.isAfter(to)) continue;
                    double amount=day.getValue();
                    byType.merge(leave.type(),amount,Double::sum);
                    months.get(YearMonth.from(date).toString()).merge(leave.type(),amount,Double::sum);
                    if (leave.paid()) paid+=amount; else unpaid+=amount;
                }
            }
            if (page+1>=result.totalPages()) break;
        }
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("from",from); result.put("to",to); result.put("requestCount",requests);
        result.put("requestsByStatus",statuses); result.put("pendingRequests",statuses.getOrDefault("PENDING",0L));
        result.put("approvedDaysByType",byType); result.put("monthlyApprovedDaysByType",months);
        result.put("paidDays",paid); result.put("unpaidDays",unpaid);
        result.put("averageDecisionSeconds",decisions==0?null:decisionSeconds/decisions);
        result.put("decidedRequests",decisions); result.put("dayTotalsComplete",legacy==0);
        result.put("legacyApprovedRequestsWithoutDailyAllocation",legacy);
        return result;
    }
}
