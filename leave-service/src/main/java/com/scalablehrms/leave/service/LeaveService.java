package com.scalablehrms.leave.service;

import com.scalablehrms.leave.domain.*;
import com.scalablehrms.leave.dto.*;
import com.scalablehrms.leave.repo.LeaveLedgerRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class LeaveService {
    private final LeaveLedgerRepository ledgers;
    private final MongoTemplate mongo;
    private final NotificationService notifications;
    private final int annualDays;
    private final int sickDays;

    public LeaveService(LeaveLedgerRepository ledgers, MongoTemplate mongo, NotificationService notifications,
                        @Value("${hrms.leave.annual-days:20}") int annualDays,
                        @Value("${hrms.leave.sick-days:10}") int sickDays) {
        this.ledgers = ledgers;
        this.mongo = mongo;
        this.notifications = notifications;
        this.annualDays = annualDays;
        this.sickDays = sickDays;
    }

    private int allowance(LeaveType type) { return type == LeaveType.ANNUAL ? annualDays : sickDays; }
    private String ledgerId(String employeeId, int year, LeaveType type) {
        return employeeId + ":" + year + ":" + type.name();
    }

    public LeaveView submit(String employeeId, CreateLeaveRequest input) {
        LocalDate start = input.startDate(), end = input.endDate();
        if (end.isBefore(start)) throw new IllegalArgumentException("End date cannot precede start date");
        if (start.isBefore(LocalDate.now())) throw new IllegalArgumentException("Cannot request leave starting in the past");
        if (start.getYear() != end.getYear())
            throw new IllegalArgumentException("Submit separate leave requests for different calendar years");
        int days = Math.toIntExact(ChronoUnit.DAYS.between(start, end) + 1);
        if (days > allowance(input.type()))
            throw new IllegalArgumentException("Requested days exceed annual entitlement for this leave type");
        String key = ledgerId(employeeId, start.getYear(), input.type());
        LeaveEntry e = new LeaveEntry();
        e.setId(UUID.randomUUID().toString());
        e.setStartDate(start);
        e.setEndDate(end);
        e.setDays(days);
        e.setReason(input.reason().trim());
        e.setStatus(LeaveStatus.PENDING);
        e.setCreatedAt(Instant.now());

        // @Version makes concurrent additions to one employee's ledger conflict rather than overwrite.
        for (int attempt = 0; attempt < 7; attempt++) {
            // Also prevent a person booking annual leave while already on sick leave.
            // The check is against persisted data; a future cross-type concurrency lock
            // is needed for strict parallel requests affecting different ledgers.
            for (LeaveLedger other : ledgers.findByEmployeeId(employeeId)) {
                if (other.getYear() == start.getYear() && other.getType() != input.type()) {
                    for (LeaveEntry prior : other.getRequests()) {
                        if (prior.getStatus() != LeaveStatus.REJECTED &&
                            !end.isBefore(prior.getStartDate()) && !start.isAfter(prior.getEndDate())) {
                            throw new ResponseStatusException(HttpStatus.CONFLICT,
                                "Overlapping leave of another type exists");
                        }
                    }
                }
            }
            LeaveLedger ledger = ledgers.findById(key).orElseGet(
                () -> LeaveLedger.fresh(employeeId, start.getYear(), input.type(), allowance(input.type())));
            for (LeaveEntry previous : ledger.getRequests()) {
                if (previous.getStatus() != LeaveStatus.REJECTED &&
                    !end.isBefore(previous.getStartDate()) && !start.isAfter(previous.getEndDate())) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Overlapping pending or approved leave exists");
                }
            }
            ledger.getRequests().add(e);
            try {
                ledgers.save(ledger);
                return LeaveView.of(employeeId, input.type(), e);
            } catch (OptimisticLockingFailureException | DuplicateKeyException concurrentChange) {
                // Retry against the latest ledger snapshot; conflicts are checked again above.
            }
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Concurrent update; please retry");
    }

    public List<LeaveView> mine(String employeeId, Integer year) {
        return ledgers.findByEmployeeId(employeeId).stream()
            .filter(l -> year == null || l.getYear() == year)
            .flatMap(l -> l.getRequests().stream().map(e -> LeaveView.of(employeeId, l.getType(), e)))
            .sorted(Comparator.comparing(LeaveView::createdAt).reversed()).toList();
    }

    public Map<String, Object> balance(String employeeId, int year) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("employeeId", employeeId);
        result.put("year", year);
        for (LeaveType t : LeaveType.values()) {
            LeaveLedger ledger = ledgers.findById(ledgerId(employeeId, year, t)).orElse(null);
            int entitled = ledger == null ? allowance(t) : ledger.getAllowance();
            result.put(t.name().toLowerCase(Locale.ROOT),
                Map.of("entitled", entitled, "used", entitled - (ledger == null ? entitled : ledger.getRemainingDays()),
                       "remaining", ledger == null ? entitled : ledger.getRemainingDays()));
        }
        return result;
    }

    public List<LeaveView> pending(int year) {
        return ledgers.findByYear(year).stream()
            .flatMap(l -> l.getRequests().stream()
                .filter(e -> e.getStatus() == LeaveStatus.PENDING)
                .map(e -> LeaveView.of(l.getEmployeeId(), l.getType(), e)))
            .sorted(Comparator.comparing(LeaveView::createdAt)).toList();
    }

    public LeaveView decide(String employeeId, String leaveId, boolean approve, String managerId) {
        // The ledger lives in ONE Mongo document: balance deduction + status transition is atomic.
        LeaveLedger ledger = ledgers.findByEmployeeId(employeeId).stream()
            .filter(l -> l.getRequests().stream().anyMatch(e -> e.getId().equals(leaveId)))
            .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Leave not found"));
        LeaveEntry entry = ledger.getRequests().stream().filter(e -> e.getId().equals(leaveId))
            .findFirst().orElseThrow();
        if (entry.getStatus() != LeaveStatus.PENDING)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Leave was already decided");

        Criteria match = Criteria.where("_id").is(ledger.getId())
            .and("requests").elemMatch(Criteria.where("id").is(leaveId).and("status").is(LeaveStatus.PENDING));
        if (approve) match = match.and("remainingDays").gte(entry.getDays());
        Query q = new Query(match);
        Update update = new Update().set("requests.$.status", approve ? LeaveStatus.APPROVED : LeaveStatus.REJECTED)
            .set("requests.$.reviewedBy", managerId)
            .set("requests.$.decidedAt", Instant.now())
            .inc("version", 1);
        if (approve) update.inc("remainingDays", -entry.getDays());
        LeaveLedger after = mongo.findAndModify(q, update,
            FindAndModifyOptions.options().returnNew(true), LeaveLedger.class);
        if (after == null) throw new ResponseStatusException(HttpStatus.CONFLICT,
            "Request changed concurrently or insufficient leave balance");
        LeaveEntry saved = after.getRequests().stream().filter(e -> e.getId().equals(leaveId))
            .findFirst().orElseThrow();
        try {
            notifications.recordAndPublish(employeeId, leaveId,
                approve ? "Your leave request was approved" : "Your leave request was rejected");
        } catch (RuntimeException notificationFailure) {
            // The decision remains valid. UI can still query the definitive leave status.
            org.slf4j.LoggerFactory.getLogger(LeaveService.class)
                .error("Leave decision saved, notification delivery failed for {}", leaveId, notificationFailure);
        }
        return LeaveView.of(employeeId, after.getType(), saved);
    }
}
