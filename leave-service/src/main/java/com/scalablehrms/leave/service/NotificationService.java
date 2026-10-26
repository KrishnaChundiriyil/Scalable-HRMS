package com.scalablehrms.leave.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scalablehrms.leave.domain.HrNotification;
import com.scalablehrms.leave.repo.NotificationRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class NotificationService implements MessageListener {
    private static final String CHANNEL = "hrms:leave:notification-events";
    private final NotificationRepository repo;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    public NotificationService(NotificationRepository repo, StringRedisTemplate redis, ObjectMapper mapper) {
        this.repo=repo; this.redis=redis; this.mapper=mapper;
    }

    public SseEmitter subscribe(String verifiedEmployeeId) {
        SseEmitter emitter = new SseEmitter(30L * 60 * 1000);
        var list = subscribers.computeIfAbsent(verifiedEmployeeId, k -> new CopyOnWriteArrayList<>());
        list.add(emitter);
        emitter.onCompletion(() -> list.remove(emitter));
        emitter.onTimeout(() -> { list.remove(emitter); emitter.complete(); });
        emitter.onError(error -> list.remove(emitter));
        try { emitter.send(SseEmitter.event().name("connected").data("ready")); }
        catch (Exception e) { list.remove(emitter); emitter.completeWithError(e); }
        return emitter;
    }
    public List<HrNotification> recent(String id) {
        return repo.findTop50ByRecipientIdOrderByCreatedAtDesc(id);
    }
    public void recordAndPublish(String recipientId, String leaveId, String message) {
        HrNotification event = repo.save(new HrNotification(UUID.randomUUID().toString(),
            recipientId, leaveId, message, Instant.now()));
        try { redis.convertAndSend(CHANNEL, mapper.writeValueAsString(event)); }
        catch (Exception e) { throw new IllegalStateException("Notification publish failed", e); }
    }
    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            HrNotification event = mapper.readValue(message.getBody(), HrNotification.class);
            for (SseEmitter emitter : subscribers.getOrDefault(event.getRecipientId(), new CopyOnWriteArrayList<>())) {
                try { emitter.send(SseEmitter.event().name("leave-update").id(event.getId()).data(event, MediaType.APPLICATION_JSON)); }
                catch (Exception e) { emitter.complete(); subscribers.get(event.getRecipientId()).remove(emitter); }
            }
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(getClass()).error("Notification event decoding failed", e);
        }
    }
    public static String channel() { return CHANNEL; }
}
