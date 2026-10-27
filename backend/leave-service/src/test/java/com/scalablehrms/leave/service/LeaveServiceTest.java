package com.scalablehrms.leave.service;

import com.scalablehrms.leave.config.LeavePolicy;
import com.scalablehrms.leave.domain.*;
import com.scalablehrms.leave.dto.*;
import com.scalablehrms.leave.repo.LeaveAccountRepository;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LeaveServiceTest {
    static LeavePolicy policy() {
        var policy = spy(new LeavePolicy());
        doReturn(LocalDate.of(2026, 1, 1)).when(policy).today();
        policy.setTypes(new LinkedHashMap<>(Map.of(
            "ANNUAL", new LeavePolicy.TypePolicy(20, 20, true, LeavePolicy.Counting.WORKING_DAYS, false),
            "SICK", new LeavePolicy.TypePolicy(10, 10, true, LeavePolicy.Counting.WORKING_DAYS, false),
            "MATERNITY", new LeavePolicy.TypePolicy(-1, 182, true, LeavePolicy.Counting.CALENDAR_DAYS, true))));
        return policy;
    }
    @Test void weekendsAndHolidaysDoNotCostOrdinaryLeave() {
        var policy = policy();
        policy.setHolidays(Set.of(LocalDate.of(2026, 11, 2)));
        assertEquals(Map.of("2026", 2.0), policy.chargedDays("ANNUAL", LocalDate.of(2026, 10, 30), LocalDate.of(2026, 11, 3)));
    }
    @Test void crossYearMaternityCountsCalendarDays() {
        assertEquals(Map.of("2026", 2.0, "2027", 2.0), policy().chargedDays("MATERNITY",
            LocalDate.of(2026, 12, 30), LocalDate.of(2027, 1, 2)));
    }
    @Test void invalidRangesAndWeekendOnlyRequestsAreRejected() {
        var policy = policy();
        assertThrows(IllegalArgumentException.class, () -> policy.chargedDays("ANNUAL",
            LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 2)));
        assertThrows(IllegalArgumentException.class, () -> policy.chargedDays("ANNUAL",
            LocalDate.of(2026, 10, 31), LocalDate.of(2026, 11, 1)));
        assertThrows(IllegalArgumentException.class, () -> policy.chargedDays("UNKNOWN",
            LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 3)));
        assertThrows(IllegalArgumentException.class, () -> policy.chargedDays("SICK",
            LocalDate.of(2026, 11, 2), LocalDate.of(2026, 12, 1)));
    }
    @Test void crossTypeOverlapIsRejectedBeforeSave() {
        var store = mock(AccountStore.class);
        var repo = mock(LeaveAccountRepository.class);
        var account = LeaveAccount.fresh("EMP1");
        account.requests().add(new LeaveRequest("old", "SICK", LocalDate.of(2026,11,2), LocalDate.of(2026,11,3),
            2, Map.of("2026",2), true, "Existing leave", LeaveStatus.PENDING, Instant.now(), null,
            null, null, null, null, null, null));
        when(store.load("EMP1")).thenReturn(account);
        var service = new LeaveService(repo, store, policy());
        var error = assertThrows(ResponseStatusException.class, () -> service.submit("EMP1",
            new CreateLeaveRequest("ANNUAL", LocalDate.of(2026,11,2), LocalDate.of(2026,11,3), "Family appointment"), null));
        assertEquals(409, error.getStatusCode().value());
        verifyNoInteractions(repo);
    }
}
