package com.scalablehrms.leave.service;

import com.scalablehrms.leave.domain.LeaveType;
import com.scalablehrms.leave.dto.CreateLeaveRequest;
import com.scalablehrms.leave.repo.LeaveLedgerRepository;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LeaveServiceTest {
    @Test
    void rejectsDateRangeInReverse() {
        var repo = mock(LeaveLedgerRepository.class);
        var service = new LeaveService(repo, mock(MongoTemplate.class), mock(NotificationService.class), 20, 10);
        var request = new CreateLeaveRequest(LeaveType.ANNUAL, LocalDate.now().plusDays(4),
            LocalDate.now().plusDays(3), "Family appointment");
        assertThrows(IllegalArgumentException.class, () -> service.submit("EMP001", request));
        verifyNoInteractions(repo);
    }

    @Test
    void rejectsOverlappingLeaveRequest() {
        var repo = mock(LeaveLedgerRepository.class);
        var service = new LeaveService(repo, mock(MongoTemplate.class), mock(NotificationService.class), 20, 10);
        LocalDate start = LocalDate.now().plusDays(3);
        // Use a future year offset to avoid test failures at end-of-year.
        if (start.getYear() != start.plusDays(2).getYear()) start = LocalDate.now().plusMonths(2);
        var existing = com.scalablehrms.leave.domain.LeaveLedger.fresh("EMP001", start.getYear(), LeaveType.ANNUAL, 20);
        var e = new com.scalablehrms.leave.domain.LeaveEntry();
        e.setId("old"); e.setStartDate(start); e.setEndDate(start.plusDays(2));
        e.setStatus(com.scalablehrms.leave.domain.LeaveStatus.PENDING);
        existing.getRequests().add(e);
        when(repo.findByEmployeeId(any())).thenReturn(java.util.List.of(existing));
        when(repo.findById(any())).thenReturn(Optional.of(existing));
        final LocalDate requested = start.plusDays(1);
        var request = new CreateLeaveRequest(LeaveType.ANNUAL, requested,
            requested, "Family appointment");
        assertThrows(ResponseStatusException.class, () -> service.submit("EMP001", request));
    }
}
