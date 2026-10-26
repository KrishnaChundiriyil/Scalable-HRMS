package com.scalablehrms.leave.domain;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection="hr_notifications")
@org.springframework.data.mongodb.core.index.CompoundIndex(name="recipient_history", def="{'recipientId':1,'createdAt':1,'_id':1}")
public class HrNotification {
    @Id private String id;
    private String recipientId;
    private String leaveId;
    private String message;
    private Instant createdAt;

    public HrNotification() { }
    public HrNotification(String id, String recipientId, String leaveId, String message, Instant createdAt) {
        this.id=id; this.recipientId=recipientId; this.leaveId=leaveId; this.message=message; this.createdAt=createdAt;
    }
    public void setId(String id) { this.id = id; }
    public void setRecipientId(String recipientId) { this.recipientId = recipientId; }
    public void setLeaveId(String leaveId) { this.leaveId = leaveId; }
    public void setMessage(String message) { this.message = message; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public String getId() { return id; }
    public String getRecipientId() { return recipientId; }
    public String getLeaveId() { return leaveId; }
    public String getMessage() { return message; }
    public Instant getCreatedAt() { return createdAt; }
}
