package com.scalablehrms.leave.domain;

import java.util.ArrayList;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.index.CompoundIndex;

// One bounded employee/year/type document; approval and balance changes are atomic in MongoDB.
@Document(collection = "leave_ledgers")
@CompoundIndex(name="year_requeststatus", def="{'year':1, 'requests.status':1}")
public class LeaveLedger {
    @Id private String id;
    @Version private Long version;
    @Indexed private String employeeId;
    private int year;
    private LeaveType type;
    private int allowance;
    private int remainingDays;
    private List<LeaveEntry> requests = new ArrayList<>();

    public static LeaveLedger fresh(String employeeId, int year, LeaveType type, int allowance) {
        LeaveLedger l = new LeaveLedger();
        l.id = employeeId + ":" + year + ":" + type.name();
        l.employeeId = employeeId;
        l.year = year;
        l.type = type;
        l.allowance = allowance;
        l.remainingDays = allowance;
        return l;
    }
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public String getEmployeeId() { return employeeId; }
    public void setEmployeeId(String employeeId) { this.employeeId = employeeId; }
    public int getYear() { return year; }
    public void setYear(int year) { this.year = year; }
    public LeaveType getType() { return type; }
    public void setType(LeaveType type) { this.type = type; }
    public int getAllowance() { return allowance; }
    public void setAllowance(int allowance) { this.allowance = allowance; }
    public int getRemainingDays() { return remainingDays; }
    public void setRemainingDays(int remainingDays) { this.remainingDays = remainingDays; }
    public List<LeaveEntry> getRequests() { return requests; }
    public void setRequests(List<LeaveEntry> requests) { this.requests = requests; }
}
