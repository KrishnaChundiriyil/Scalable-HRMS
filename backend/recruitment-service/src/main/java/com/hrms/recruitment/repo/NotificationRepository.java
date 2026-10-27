package com.hrms.recruitment.repo;

import com.hrms.recruitment.model.Notification;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface NotificationRepository extends MongoRepository<Notification, String> {
    java.util.List<Notification> findTop50ByOrderByCreatedAtDesc();
}
