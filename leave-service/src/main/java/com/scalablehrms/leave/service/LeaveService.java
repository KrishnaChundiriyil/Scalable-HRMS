package com.scalablehrms.leave.service;

import com.scalablehrms.leave.config.LeavePolicy;
import com.scalablehrms.leave.domain.*;
import com.scalablehrms.leave.dto.*;
import com.scalablehrms.leave.repo.LeaveAccountRepository;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class LeaveService {
    private final LeaveAccountRepository accounts;
    private final AccountStore store;
    private final LeavePolicy policy;

    public LeaveService(LeaveAccountRepository accounts, AccountStore store, LeavePolicy policy) {
        this.accounts = accounts;
        this.store = store;
        this.policy = policy;
    }

    /** One version-checked replacement atomically commits request, derived balances and outbox. */
    private LeaveView mutate(String employee, Function<LeaveAccount, LeaveRequest> operation) {
        for (int attempt = 0; attempt < 12; attempt++) {
            LeaveAccount account = store.load(employee);
            LeaveRequest result = operation.apply(account);
            if (account.outbox().size() > 1000) throw conflict("Notification backlog; please retry later");
            try {
                accounts.save(account);
                return LeaveView.of(employee, result);
            } catch (OptimisticLockingFailureException | DuplicateKeyException concurrent) {
                // Re-read and re-check overlap/balance/status against the winning write.
            }
        }
        throw conflict("Concurrent updates; please retry");
    }

    public LeaveView submit(String employee, CreateLeaveRequest input, String idempotencyKey) {
        String reason = text(input.reason(), "Reason");
        if (idempotencyKey != null && !idempotencyKey.matches("[A-Za-z0-9_.:-]{1,128}"))
            throw new IllegalArgumentException("Invalid Idempotency-Key");
        return mutate(employee, account -> {
            if (idempotencyKey != null) {
                for (LeaveRequest prior : account.requests()) {
                    if (idempotencyKey.equals(prior.idempotencyKey())) {
                        if (!prior.type().equals(input.type()) || !prior.startDate().equals(input.startDate())
                            || !prior.endDate().equals(input.endDate()) || !prior.reason().equals(reason))
                            throw conflict("Idempotency-Key was already used for another request");
                        return prior;
                    }
                }
            }
            var charges = policy.chargedDays(input.type(), input.startDate(), input.endDate());
            var rule = policy.rule(input.type());
            if (account.requests().size() >= policy.getMaxRequests())
                throw conflict("Employee history capacity reached; administrator archival required");
            for (LeaveRequest prior : account.requests()) {
                if ((prior.status() == LeaveStatus.PENDING || prior.status() == LeaveStatus.APPROVED)
                    && !input.endDate().isBefore(prior.startDate()) && !input.startDate().isAfter(prior.endDate()))
                    throw conflict("Overlapping pending or approved leave exists");
            }
            // Pending requests do not reserve balance, but a request must fit the current balance.
            ensureBalance(account, input.type(), charges);
            charges.keySet().forEach(year -> account.allowances().putIfAbsent(year + ":" + input.type(), rule.yearlyAllowance()));
            var request = new LeaveRequest(UUID.randomUUID().toString(), input.type(), input.startDate(), input.endDate(),
                charges.values().stream().mapToInt(Integer::intValue).sum(), charges, rule.paid(), reason,
                LeaveStatus.PENDING, Instant.now(), null, null, null, null, null, null, idempotencyKey);
            account.requests().add(request);
            return request;
        });
    }

    public LeaveView decide(String employee, String id, DecisionRequest input, String reviewer) {
        if (employee.equals(reviewer)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot approve your own leave");
        if (input.approve() == null) throw new IllegalArgumentException("approve is required");
        String rejection = input.approve() ? null : text(input.rejectionReason(), "Rejection reason");
        return mutate(employee, account -> {
            LeaveRequest prior = find(account, id);
            if (prior.status() != LeaveStatus.PENDING) throw conflict("Only pending leave can be decided");
            if (prior.startDate().isBefore(policy.today())) throw conflict("Leave has already started");
            String eligibility = input.eligibilityNote();
            if (input.approve()) {
                ensureBalance(account, prior.type(), prior.chargedDays());
                if (policy.rule(prior.type()).eligibilityReviewRequired())
                    eligibility = text(eligibility, "Eligibility review note");
            }
            var decided = prior.decided(input.approve(), reviewer, rejection, eligibility);
            account.requests().set(account.requests().indexOf(prior), decided);
            enqueue(account, id, input.approve() ? "Your leave request was approved" : "Your leave request was rejected: " + rejection);
            return decided;
        });
    }

    public LeaveView cancel(String employee, String id, String reason) {
        String why = text(reason, "Cancellation reason");
        return mutate(employee, account -> {
            LeaveRequest prior = find(account, id);
            if (prior.status() != LeaveStatus.PENDING && prior.status() != LeaveStatus.APPROVED)
                throw conflict("Only pending or approved leave can be cancelled");
            if (!policy.today().isBefore(prior.startDate()))
                throw conflict("Cancellation is allowed only before the leave start date");
            var cancelled = prior.cancelled(why);
            account.requests().set(account.requests().indexOf(prior), cancelled);
            enqueue(account, id, "Your leave request was cancelled");
            return cancelled;
        });
    }

    private void enqueue(LeaveAccount account, String leaveId, String message) {
        account.outbox().add(new HrNotification(UUID.randomUUID().toString(), account.id(), leaveId, message, Instant.now()));
    }

    private int allowance(LeaveAccount account, String type, String year) {
        return account.allowances().getOrDefault(year + ":" + type, policy.rule(type).yearlyAllowance());
    }

    private int used(LeaveAccount account, String type, String year) {
        return account.requests().stream().filter(r -> r.type().equals(type) && r.status() == LeaveStatus.APPROVED)
            .mapToInt(r -> r.chargedDays().getOrDefault(year, 0)).sum();
    }

    private void ensureBalance(LeaveAccount account, String type, Map<String,Integer> charges) {
        charges.forEach((year, days) -> {
            int allowance = allowance(account, type, year);
            if (allowance >= 0 && used(account, type, year) + days > allowance)
                throw conflict("Insufficient " + type + " balance for " + year);
        });
    }

    public Map<String,Object> balance(String employee, int year) {
        validYear(year);
        LeaveAccount account = store.load(employee);
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("employeeId", employee);
        result.put("year", year);
        policy.getTypes().forEach((type, rule) -> {
            int entitled = allowance(account, type, Integer.toString(year));
            int used = used(account, type, Integer.toString(year));
            var value = new LinkedHashMap<String,Object>();
            value.put("entitled", entitled < 0 ? null : entitled);
            value.put("used", used);
            value.put("remaining", entitled < 0 ? null : Math.max(0, entitled - used));
            value.put("maxPerRequest", rule.maxPerRequest());
            value.put("limitScope", entitled < 0 ? "PER_REQUEST" : "YEARLY");
            value.put("paid", rule.paid());
            value.put("counting", rule.counting());
            result.put(type.toLowerCase(Locale.ROOT), value);
        });
        return result;
    }

    public List<LeaveView> mine(String employee, Integer year) {
        if (year != null) validYear(year);
        return store.load(employee).requests().stream().filter(r -> inYear(r, year))
            .map(r -> LeaveView.of(employee, r)).sorted(Comparator.comparing(LeaveView::createdAt).reversed()).toList();
    }

    public List<LeaveView> pending(int year) {
        validYear(year);
        return store.all().stream().flatMap(a -> a.requests().stream()
            .filter(r -> r.status() == LeaveStatus.PENDING && inYear(r, year)).map(r -> LeaveView.of(a.id(), r)))
            .sorted(Comparator.comparing(LeaveView::createdAt)).toList();
    }

    public List<LeaveView> approved(String employee, LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from) || to.isAfter(from.plusDays(366)))
            throw new IllegalArgumentException("Payroll range must be ordered and no longer than 366 days");
        return mine(employee, null).stream().filter(r -> r.status() == LeaveStatus.APPROVED
            && !r.endDate().isBefore(from) && !r.startDate().isAfter(to)).toList();
    }

    private boolean inYear(LeaveRequest request, Integer year) {
        return year == null || (request.startDate().getYear() <= year && request.endDate().getYear() >= year);
    }
    private void validYear(int year) {
        if (year < 2000 || year > 2200) throw new IllegalArgumentException("Year must be between 2000 and 2200");
    }
    private LeaveRequest find(LeaveAccount account, String id) {
        return account.requests().stream().filter(r -> r.id().equals(id)).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Leave not found"));
    }
    private static String text(String value, String name) {
        if (value == null || value.trim().length() < 5 || value.trim().length() > 500)
            throw new IllegalArgumentException(name + " must contain 5 to 500 non-padding characters");
        return value.trim();
    }
    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
