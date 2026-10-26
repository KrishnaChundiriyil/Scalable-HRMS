package com.scalablehrms.leave.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scalablehrms.leave.domain.*;
import com.scalablehrms.leave.repo.NotificationRepository;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class NotificationService implements MessageListener {
    private static final String CHANNEL = "hrms:leave:notification-events";
    private final NotificationRepository repo;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final MongoTemplate mongo;
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    public NotificationService(NotificationRepository repo, StringRedisTemplate redis, ObjectMapper mapper, MongoTemplate mongo) {
        this.repo = repo;
        this.redis = redis;
        this.mapper = mapper;
        this.mongo = mongo;
    }

    /** Durable outbox retries independently of the employee's approval HTTP request. */
    @Scheduled(fixedDelayString = "${hrms.notifications.outbox-delay-ms:1000}")
    public void dispatchOutbox() {
        try {
            for (LeaveAccount account : mongo.find(new Query(Criteria.where("outbox.0").exists(true)).limit(100), LeaveAccount.class)) {
                for (HrNotification event : account.outbox()) {
                    try {
                        Query id = Query.query(Criteria.where("_id").is(event.getId()));
                        Update insert = new Update().setOnInsert("recipientId", event.getRecipientId())
                            .setOnInsert("leaveId", event.getLeaveId()).setOnInsert("message", event.getMessage())
                            .setOnInsert("createdAt", event.getCreatedAt());
                        mongo.upsert(id, insert, HrNotification.class);
                        redis.convertAndSend(CHANNEL, mapper.writeValueAsString(event));
                        // Increment version so a simultaneous domain save cannot restore a drained outbox.
                        mongo.updateFirst(Query.query(Criteria.where("_id").is(account.id())
                            .and("outbox.id").is(event.getId())),
                            new Update().pull("outbox", new org.bson.Document("_id", event.getId())).inc("version", 1), LeaveAccount.class);
                    } catch (Exception failure) {
                        LoggerFactory.getLogger(getClass()).warn("notificationId={} delivery=pending cause={}",
                            event.getId(), failure.getClass().getSimpleName());
                        break;
                    }
                }
            }
        } catch (RuntimeException unavailable) {
            LoggerFactory.getLogger(getClass()).warn("notification-outbox scan=unavailable");
        }
    }

    public List<HrNotification> recent(String employee, String after, int limit) {
        return history(employee, after, null, limit);
    }

    public List<HrNotification> history(String employee, String after, String before, int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Notification limit must be 1 to 100");
        if (after != null && before != null) throw new IllegalArgumentException("Use either after or before, not both");
        Criteria recipient = Criteria.where("recipientId").is(employee);
        String cursorId = after != null ? after : before;
        if (cursorId != null && !cursorId.isBlank()) {
            HrNotification cursor = repo.findById(cursorId).filter(n -> n.getRecipientId().equals(employee))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification cursor not found"));
            Criteria time = after != null ? Criteria.where("createdAt").gt(cursor.getCreatedAt()) : Criteria.where("createdAt").lt(cursor.getCreatedAt());
            Criteria id = after != null ? Criteria.where("_id").gt(cursorId) : Criteria.where("_id").lt(cursorId);
            Criteria later = new Criteria().orOperator(time,
                new Criteria().andOperator(Criteria.where("createdAt").is(cursor.getCreatedAt()), id));
            recipient = new Criteria().andOperator(recipient, later);
        }
        Sort.Direction direction = after == null || after.isBlank() ? Sort.Direction.DESC : Sort.Direction.ASC;
        return mongo.find(new Query(recipient).with(Sort.by(direction, "createdAt", "id")).limit(limit), HrNotification.class);
    }

    public SseEmitter subscribe(String employee, String lastEventId, Instant expires) {
        long ttl = Math.min(30 * 60 * 1000L, java.time.Duration.between(Instant.now(), expires).toMillis());
        if (ttl <= 0) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token expired");
        List<HrNotification> replay = lastEventId == null || lastEventId.isBlank() ? List.of() : recent(employee, lastEventId, 100);
        SseEmitter emitter = new SseEmitter(ttl);
        subscribers.compute(employee, (key, list) -> {
            if (list == null) list = new CopyOnWriteArrayList<>();
            if (list.size() >= 5) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many notification streams");
            list.add(emitter);
            return list;
        });
        emitter.onCompletion(() -> remove(employee, emitter));
        emitter.onTimeout(() -> { remove(employee, emitter); emitter.complete(); });
        emitter.onError(error -> remove(employee, emitter));
        try {
            emitter.send(SseEmitter.event().name("connected").data("ready; use notification history to reconcile missed events"));
            for (HrNotification event : replay) send(emitter, event);
        } catch (Exception failure) {
            remove(employee, emitter);
            emitter.completeWithError(failure);
        }
        return emitter;
    }

    private void remove(String employee, SseEmitter emitter) {
        subscribers.computeIfPresent(employee, (key, list) -> { list.remove(emitter); return list.isEmpty() ? null : list; });
    }

    @Scheduled(fixedDelay = 15000)
    public void heartbeat() {
        subscribers.forEach((employee, emitters) -> {
            for (SseEmitter emitter : emitters) {
                try { emitter.send(SseEmitter.event().comment("heartbeat")); }
                catch (Exception failure) { remove(employee, emitter); emitter.complete(); }
            }
        });
    }

    private void send(SseEmitter emitter, HrNotification event) throws java.io.IOException {
        emitter.send(SseEmitter.event().name("leave-update").id(event.getId()).data(event, MediaType.APPLICATION_JSON));
    }
    @Override public void onMessage(Message message, byte[] pattern) {
        try {
            HrNotification event = mapper.readValue(message.getBody(), HrNotification.class);
            for (SseEmitter emitter : subscribers.getOrDefault(event.getRecipientId(), new CopyOnWriteArrayList<>())) {
                try { send(emitter, event); }
                catch (Exception failure) { remove(event.getRecipientId(), emitter); emitter.complete(); }
            }
        } catch (Exception failure) {
            LoggerFactory.getLogger(getClass()).warn("notification-event decode=failed");
        }
    }
    public static String channel() { return CHANNEL; }
}
