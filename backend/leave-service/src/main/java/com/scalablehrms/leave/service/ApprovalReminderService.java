package com.scalablehrms.leave.service;

import com.scalablehrms.leave.config.*;
import com.scalablehrms.leave.domain.*;
import com.scalablehrms.leave.repo.LeaveAccountRepository;
import java.time.*;
import java.util.*;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.*;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Application reminders are persisted through the same outbox as decision messages. */
@Service
public class ApprovalReminderService {
    private final ReminderPolicy config;
    private final LeavePolicy policy;
    private final EmployeeDirectoryClient directory;
    private final LeaveAccountRepository accounts;
    private final AccountStore store;
    private final MongoTemplate mongo;
    private final String internalToken;
    public ApprovalReminderService(ReminderPolicy config,LeavePolicy policy,EmployeeDirectoryClient directory,
            LeaveAccountRepository accounts,AccountStore store,MongoTemplate mongo,
            @Value("${hrms.employee-service.internal-token:}") String internalToken) {
        this.config=config; this.policy=policy; this.directory=directory; this.accounts=accounts;
        this.store=store; this.mongo=mongo; this.internalToken=internalToken;
    }
    @jakarta.annotation.PostConstruct public void describeConfiguration() {
        if (config.isEnabled() && internalToken.isBlank())
            LoggerFactory.getLogger(getClass()).info("approval-reminders awaiting Employee Service internal credential");
    }
    @Scheduled(fixedDelayString="${hrms.reminders.scan-delay-ms:60000}")
    public void scheduled() {
        try { process(Instant.now()); }
        catch (RuntimeException error) { LoggerFactory.getLogger(getClass()).warn("approval-reminders scan=unavailable cause={}",error.getClass().getSimpleName()); }
    }
    public int process(Instant now) {
        if (!config.isEnabled() || internalToken.isBlank()) return 0;
        store.importLegacy();
        int queued=0;
        String last=null;
        while (true) {
            var pending=Criteria.where("requests").elemMatch(Criteria.where("status").is(LeaveStatus.PENDING)
                .and("createdAt").lte(now.minus(Duration.ofHours(config.getAfterHours()))));
            if (last!=null) pending=new Criteria().andOperator(pending,Criteria.where("_id").gt(last));
            var batch=mongo.find(new Query(pending).with(Sort.by("id")).limit(100),LeaveAccount.class);
            if (batch.isEmpty()) return queued;
            for (var account:batch) {
                last=account.id();
                try { queued+=queue(account.id(),now); }
                catch (RuntimeException unavailable) {
                    LoggerFactory.getLogger(getClass()).warn("approval-reminder account={} outcome=deferred cause={}",account.id(),unavailable.getClass().getSimpleName());
                }
            }
        }
    }
    private int queue(String employee,Instant now) {
        String bearer="Bearer "+internalToken;
        var person=directory.requireActive(employee,bearer);
        if (person.managerId()==null || person.managerId().equals(employee)) return 0;
        directory.requireActive(person.managerId(),bearer);
        for (int attempt=0;attempt<12;attempt++) {
            var account=store.load(employee);
            int added=0;
            for (var leave:account.requests()) {
                if (leave.status()!=LeaveStatus.PENDING || leave.startDate().isBefore(policy.today())
                    || leave.createdAt().plus(Duration.ofHours(config.getAfterHours())).isAfter(now)) continue;
                Instant previous=account.reminderAt().get(leave.id());
                if (previous!=null && previous.plus(Duration.ofHours(config.getRepeatHours())).isAfter(now)) continue;
                if (account.outbox().size()>=1000) break;
                account.outbox().add(new HrNotification(UUID.randomUUID().toString(),person.managerId(),leave.id(),
                    "A leave request from "+employee+" is awaiting your review",now));
                account.reminderAt().put(leave.id(),now);
                added++;
            }
            if (added==0) return 0;
            store.checkSize(account);
            try { accounts.save(account); return added; }
            catch (OptimisticLockingFailureException | DuplicateKeyException concurrent) { /* reload status/reminder timestamp */ }
        }
        return 0;
    }
}
