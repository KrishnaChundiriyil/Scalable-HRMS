package com.hrms.recruitment.service;

import com.hrms.recruitment.model.Notification;
import com.hrms.recruitment.repo.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/** Kafka producer + notification consumer for HR events. */
@Service
public class EventService {
    private static final Logger log = LoggerFactory.getLogger(EventService.class);
    private final KafkaTemplate<String, String> kafka;
    private final NotificationRepository notifications;
    private final String topic;

    public EventService(KafkaTemplate<String, String> kafka, NotificationRepository notifications,
                        @Value("${app.kafka.topic}") String topic) {
        this.kafka = kafka;
        this.notifications = notifications;
        this.topic = topic;
    }

    /** Message format: TYPE|text. Key = type so related events share a partition. */
    public void publish(String type, String message) {
        try {
            kafka.send(topic, type, type + "|" + message).whenComplete((result,error)->{
                if(error!=null)log.warn("Kafka delivery failed for {}: {}",type,error.getMessage());
                else log.info("Kafka published {} -> {}", type, message);
            });
        } catch (Exception e) {
            log.warn("Kafka unavailable, event not published: {}", e.getMessage());
        }
    }

    @KafkaListener(topics = "${app.kafka.topic}")
    public void consume(String payload) {
        String[] p = payload.split("\\|", 2);
        Notification n = new Notification();
        n.setType(p[0]);
        n.setMessage(p.length > 1 ? p[1] : "");
        notifications.save(n);
        log.info("Kafka consumed -> notification saved: {}", payload);
    }
}
