package com.scalablehrms.leave.service;

import com.scalablehrms.leave.domain.*;
import com.scalablehrms.leave.repo.*;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/** Non-destructive lazy import of the original per-type ledgers, retaining IDs and charged days. */
@Service
public class AccountStore {
    private final LeaveAccountRepository accounts;
    private final LeaveLedgerRepository legacy;
    public AccountStore(LeaveAccountRepository accounts, LeaveLedgerRepository legacy) {
        this.accounts = accounts;
        this.legacy = legacy;
    }
    public LeaveAccount load(String employeeId) {
        return accounts.findById(employeeId).orElseGet(() -> {
            var source = legacy.findByEmployeeId(employeeId);
            var account = LeaveAccount.fresh(employeeId);
            for (LeaveLedger ledger : source) {
                account.allowances().put(ledger.getYear() + ":" + ledger.getType().name(), ledger.getAllowance());
                for (LeaveEntry old : ledger.getRequests()) {
                    account.requests().add(new LeaveRequest(old.getId(), ledger.getType().name(),
                        old.getStartDate(), old.getEndDate(), old.getDays(),
                        Map.of(Integer.toString(ledger.getYear()), old.getDays()), true,
                        old.getReason(), old.getStatus(), old.getCreatedAt(), old.getDecidedAt(),
                        old.getReviewedBy(), null, null, null, null, null));
                }
            }
            if (source.isEmpty()) return account;
            try { return accounts.insert(account); }
            catch (DuplicateKeyException concurrentImport) { return accounts.findById(employeeId).orElseThrow(); }
        });
    }
    public List<LeaveAccount> all() {
        // Import untouched old accounts before manager/report queries. Old writers must be stopped.
        legacy.findAll().stream().map(LeaveLedger::getEmployeeId).distinct().forEach(this::load);
        return accounts.findAll();
    }
}
