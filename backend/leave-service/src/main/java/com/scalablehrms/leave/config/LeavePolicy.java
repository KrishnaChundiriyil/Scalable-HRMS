package com.scalablehrms.leave.config;

import jakarta.annotation.PostConstruct;
import java.time.*;
import java.util.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Editable company policies, not an automatic statutory eligibility engine. */
@Component
@ConfigurationProperties(prefix = "hrms.leave")
public class LeavePolicy {
    public enum Counting { WORKING_DAYS, CALENDAR_DAYS }
    public record TypePolicy(int yearlyAllowance, int maxPerRequest, boolean paid,
                             Counting counting, boolean eligibilityReviewRequired) {}
    private Map<String, TypePolicy> types = new LinkedHashMap<>();
    private Set<LocalDate> holidays = new HashSet<>();
    private ZoneId timezone = ZoneId.of("Asia/Kolkata");
    private int maxRequests = 5000;
    private Set<String> halfDayTypes = new HashSet<>(Set.of("ANNUAL","SICK","CASUAL"));
    public Set<String> getHalfDayTypes() { return halfDayTypes; }
    public void setHalfDayTypes(Set<String> values) { this.halfDayTypes = values; }


    public Map<String, TypePolicy> getTypes() { return types; }
    public void setTypes(Map<String, TypePolicy> types) { this.types = types; }
    public Set<LocalDate> getHolidays() { return holidays; }
    public void setHolidays(Set<LocalDate> holidays) { this.holidays = holidays; }
    public ZoneId getTimezone() { return timezone; }
    public void setTimezone(ZoneId timezone) { this.timezone = timezone; }
    public int getMaxRequests() { return maxRequests; }
    public void setMaxRequests(int maxRequests) { this.maxRequests = maxRequests; }
    public LocalDate today() { return LocalDate.now(timezone); }

    @PostConstruct public void validate() {
        if (types.isEmpty() || timezone == null || maxRequests < 1 || maxRequests > 10000)
            throw new IllegalArgumentException("Invalid leave policy configuration");
        types.forEach((name, rule) -> {
            if (!name.matches("[A-Z][A-Z0-9_]{0,39}") || rule.counting() == null
                || rule.yearlyAllowance() < -1 || rule.maxPerRequest() < 1 || rule.maxPerRequest() > 366)
                throw new IllegalArgumentException("Invalid leave type policy: " + name);
        });
    }

    public TypePolicy rule(String type) {
        TypePolicy policy = types.get(type);
        if (policy == null) throw new IllegalArgumentException("Unknown or disabled leave type");
        return policy;
    }

    public Map<String, Double> chargedDays(String type, LocalDate start, LocalDate end) {
        return allocateByYear(dailyCharges(type,start,end,com.scalablehrms.leave.domain.LeaveSession.FULL_DAY));
    }
    public Map<String,Double> allocateByYear(Map<String,Double> daily) {
        Map<String,Double> yearly = new LinkedHashMap<>();
        daily.forEach((date,days) -> yearly.merge(date.substring(0,4),days,Double::sum));
        return yearly;
    }
    public Map<String,Double> dailyCharges(String type, LocalDate start, LocalDate end,
                                           com.scalablehrms.leave.domain.LeaveSession session) {
        if (start == null || end == null || end.isBefore(start))
            throw new IllegalArgumentException("End date cannot precede start date");
        if (start.isBefore(today())) throw new IllegalArgumentException("Cannot request leave starting in the past");
        if (java.time.temporal.ChronoUnit.DAYS.between(start,end) > 730)
            throw new IllegalArgumentException("Date range is too long");
        TypePolicy rule = rule(type);
        boolean half = session != com.scalablehrms.leave.domain.LeaveSession.FULL_DAY;
        if (half && (!halfDayTypes.contains(type) || !start.equals(end)))
            throw new IllegalArgumentException("Half days require a single date and an enabled leave type");
        Map<String,Double> result = new LinkedHashMap<>();
        for (LocalDate date=start; !date.isAfter(end); date=date.plusDays(1)) {
            if (rule.counting() == Counting.CALENDAR_DAYS || (date.getDayOfWeek()!=DayOfWeek.SATURDAY
                && date.getDayOfWeek()!=DayOfWeek.SUNDAY && !holidays.contains(date)))
                result.put(date.toString(),half ? 0.5 : 1.0);
        }
        double days = result.values().stream().mapToDouble(Double::doubleValue).sum();
        if (days == 0) throw new IllegalArgumentException("No chargeable working days in this range");
        if (days > rule.maxPerRequest()) throw new IllegalArgumentException("Leave exceeds the per-request limit");
        if (rule.yearlyAllowance() >= 0 && allocateByYear(result).values().stream().anyMatch(d -> d > rule.yearlyAllowance()))
            throw new IllegalArgumentException("Leave exceeds the yearly allowance");
        return result;
    }
}
