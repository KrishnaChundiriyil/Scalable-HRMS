package com.hrms.recruitment.controller;

import com.hrms.recruitment.model.Job;
import com.hrms.recruitment.repo.JobRepository;
import com.hrms.recruitment.service.EventService;
import com.hrms.recruitment.service.UrlShortenerService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.*;

@RestController
public class JobController {
    private final JobRepository jobs;
    private final UrlShortenerService shortener;
    private final EventService events;
    private final String baseUrl;

    public JobController(JobRepository jobs, UrlShortenerService shortener, EventService events,
                         @Value("${app.base-url}") String baseUrl) {
        this.jobs = jobs;
        this.shortener = shortener;
        this.events = events;
        this.baseUrl = baseUrl;
    }

    @PostMapping("/api/jobs")
    public Map<String, Object> create(@RequestBody Job job) {
        job.setId(null);
        Job saved = jobs.save(job);
        String longUrl = baseUrl + "/api/jobs/" + saved.getId();
        saved.setShortCode(shortener.shorten(longUrl));
        saved = jobs.save(saved);
        events.publish("JOB_PUBLISHED", "Job '" + saved.getTitle() + "' published at " + saved.getCompany());
        return view(saved);
    }

    @GetMapping("/api/jobs")
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Job j : jobs.findAll()) out.add(view(j));
        return out;
    }

    @GetMapping("/api/jobs/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String id) {
        return jobs.findById(id)
                .map(j -> ResponseEntity.ok(view(j)))
                .orElse(ResponseEntity.notFound().<Map<String, Object>>build());
    }

    private Map<String, Object> view(Job j) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", j.getId());
        m.put("title", j.getTitle());
        m.put("company", j.getCompany());
        m.put("location", j.getLocation());
        m.put("description", j.getDescription());
        m.put("shortCode", j.getShortCode());
        m.put("shortUrl", j.getShortCode() == null ? null : shortener.fullShortUrl(j.getShortCode()));
        m.put("clicks", j.getShortCode() == null ? 0 : shortener.clicks(j.getShortCode()));
        return m;
    }

    /** Short link redirect. */
    @GetMapping("/s/{code}")
    public ResponseEntity<Void> redirect(@PathVariable String code) {
        return shortener.resolve(code)
                .map(url -> ResponseEntity.status(302).location(URI.create(url)).<Void>build())
                .orElse(ResponseEntity.notFound().build());
    }

    /** Standalone shortener (e.g. interview invitation links). */
    @PostMapping("/api/shorten")
    public Map<String, String> shorten(@RequestBody Map<String, String> body) {
        String url = body.get("url");
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://")))
            throw new IllegalArgumentException("url must start with http:// or https://");
        String code = shortener.shorten(url);
        return Map.of("code", code, "shortUrl", shortener.fullShortUrl(code), "longUrl", url);
    }
}
