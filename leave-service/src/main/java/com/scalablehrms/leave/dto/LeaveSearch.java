package com.scalablehrms.leave.dto;
import com.scalablehrms.leave.domain.LeaveStatus;
import java.time.LocalDate;
public record LeaveSearch(String employeeId,String type,LeaveStatus status,LocalDate from,LocalDate to,
                          String search,int page,int size) {
    public LeaveSearch {
        if (page<0 || page>100000 || size<1 || size>100) throw new IllegalArgumentException("Invalid page or size (1 to 100)");
        if (from!=null && to!=null && to.isBefore(from)) throw new IllegalArgumentException("Invalid date range");
        if (employeeId!=null && !employeeId.matches("[A-Za-z0-9][A-Za-z0-9_.@-]{0,127}")) throw new IllegalArgumentException("Invalid employee ID");
        if (type!=null && !type.matches("[A-Z][A-Z0-9_]{0,39}")) throw new IllegalArgumentException("Invalid type filter");
        if (search!=null && search.length()>80) throw new IllegalArgumentException("Search is too long");
    }
}
