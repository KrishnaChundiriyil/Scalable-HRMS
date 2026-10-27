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
    private final org.springframework.data.mongodb.core.MongoTemplate mongo;
    private volatile boolean imported;

    public AccountStore(LeaveAccountRepository accounts, LeaveLedgerRepository legacy, org.springframework.data.mongodb.core.MongoTemplate mongo) {
        this.mongo = mongo;
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
    public synchronized void importLegacy() {
        if (imported) return;
        try (var cursor = mongo.getCollection("leave_ledgers").distinct("employeeId",String.class).iterator()) {
            while (cursor.hasNext()) load(cursor.next());
        }
        imported = true;
    }
    public void checkSize(LeaveAccount account) {
        var document = new org.bson.Document();
        mongo.getConverter().write(account,document);
        int bytes = new org.bson.RawBsonDocument(document,new org.bson.codecs.DocumentCodec()).getByteBuffer().remaining();
        if (bytes > 12*1024*1024) throw new org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.CONFLICT,"Employee history requires archival before further updates");
    }
    public List<LeaveAccount> all() {
        importLegacy();
        return accounts.findAll();
    }
}
