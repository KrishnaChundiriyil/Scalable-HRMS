package com.hrms.recruitment.controller;

import com.hrms.recruitment.model.Notification;
import com.hrms.recruitment.repo.NotificationRepository;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class NotificationController {
    private final NotificationRepository repo;
    public NotificationController(NotificationRepository repo) { this.repo = repo; }

    @GetMapping("/api/notifications")
    public List<Notification> list() { return repo.findTop50ByOrderByCreatedAtDesc(); }
}
