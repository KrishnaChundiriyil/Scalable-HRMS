package com.hrms.recruitment.controller;

import com.hrms.recruitment.model.Candidate;
import com.hrms.recruitment.model.JobApplication;
import com.hrms.recruitment.model.Job;
import com.hrms.recruitment.repo.CandidateRepository;
import com.hrms.recruitment.repo.JobApplicationRepository;
import com.hrms.recruitment.repo.JobRepository;
import com.hrms.recruitment.service.EventService;
import com.hrms.recruitment.service.ResumeStorageService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

@RestController
@RequestMapping("/api")
public class ApplicationController {
    private final JobRepository jobs;
    private final CandidateRepository candidates;
    private final JobApplicationRepository apps;
    private final ResumeStorageService storage;
    private final EventService events;

    public ApplicationController(JobRepository jobs, CandidateRepository candidates, JobApplicationRepository apps,
                                 ResumeStorageService storage, EventService events) {
        this.jobs = jobs;
        this.candidates = candidates;
        this.apps = apps;
        this.storage = storage;
        this.events = events;
    }

    @PostMapping("/candidates")
    public Candidate register(@RequestBody Candidate c) {
        c.setId(null);
        return candidates.findByEmail(c.getEmail()).orElseGet(() -> candidates.save(c));
    }

    @GetMapping("/candidates")
    public List<Candidate> candidates() { return candidates.findAll(); }

    @PostMapping(value = "/applications", consumes = "multipart/form-data")
    public ResponseEntity<?> apply(@RequestParam String jobId, @RequestParam String name, @RequestParam String email,
                                   @RequestParam(required = false) String phone,
                                   @RequestParam("resume") MultipartFile resume) throws Exception {
        Optional<Job> job = jobs.findById(jobId);
        if (job.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "Unknown jobId"));
        if (resume.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "Resume file is required"));

        Candidate cand = candidates.findByEmail(email).orElseGet(() -> {
            Candidate c = new Candidate();
            c.setName(name); c.setEmail(email); c.setPhone(phone);
            return candidates.save(c);
        });

        JobApplication app = new JobApplication();
        app.setJobId(jobId);
        app.setCandidateId(cand.getId());
        app.setCandidateName(name);
        app = apps.save(app);              // id needed first: it is the consistent-hash key
        storage.store(app, resume);        // consistent hashing picks the storage node
        app = apps.save(app);

        events.publish("APPLICATION_SUBMITTED",
                name + " applied for '" + job.get().getTitle() + "' (resume stored on " + app.getStorageNode() + ")");
        return ResponseEntity.ok(app);
    }

    @GetMapping("/applications")
    public List<JobApplication> list(@RequestParam(required = false) String jobId) {
        return jobId == null ? apps.findAll() : apps.findByJobId(jobId);
    }

    @PutMapping("/applications/{id}/status")
    public ResponseEntity<?> status(@PathVariable String id, @RequestBody Map<String, String> body) {
        return apps.findById(id).map(a -> {
            a.setStatus(body.getOrDefault("status", a.getStatus()));
            apps.save(a);
            events.publish("APPLICATION_STATUS", a.getCandidateName() + " application is now " + a.getStatus());
            return ResponseEntity.ok(a);
        }).orElse(ResponseEntity.notFound().build());
    }
}
