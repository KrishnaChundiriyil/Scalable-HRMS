package com.hrms.recruitment.controller;

import com.hrms.recruitment.model.CrawledJob;
import com.hrms.recruitment.repo.CrawledJobRepository;
import com.hrms.recruitment.service.CrawlerService;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/crawler")
public class CrawlerController {
    private final CrawlerService crawler;
    private final CrawledJobRepository repo;

    public CrawlerController(CrawlerService crawler, CrawledJobRepository repo) {
        this.crawler = crawler;
        this.repo = repo;
    }

    @PostMapping("/crawl")
    public Map<String, Object> crawl(@RequestParam String seed,
                                     @RequestParam(required = false) Integer maxPages,
                                     @RequestParam(defaultValue = "false") boolean reset) {
        if (!(seed.startsWith("http://") || seed.startsWith("https://")))
            throw new IllegalArgumentException("seed must be an http(s) URL");
        return crawler.crawl(seed, maxPages, reset);
    }

    @GetMapping("/jobs")
    public List<CrawledJob> jobs() { return repo.findAll(); }
}
