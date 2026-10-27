package com.scalablehrms.leave.repo;

import java.util.List;
import com.scalablehrms.leave.domain.HrNotification;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface NotificationRepository extends MongoRepository<HrNotification, String> {
    List<HrNotification> findTop50ByRecipientIdOrderByCreatedAtDesc(String recipientId);
}
