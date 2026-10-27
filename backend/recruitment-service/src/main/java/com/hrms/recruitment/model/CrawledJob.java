package com.hrms.recruitment.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.Instant;

@Document("crawled_jobs")
public class CrawledJob {
    @Id private String id;
    private String title;
    private String company;
    private String location;
    @Indexed(unique = true) private String sourceUrl;
    private Instant crawledAt = Instant.now();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getCompany() { return company; }
    public void setCompany(String company) { this.company = company; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public String getSourceUrl() { return sourceUrl; }
    public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }
    public Instant getCrawledAt() { return crawledAt; }
    public void setCrawledAt(Instant crawledAt) { this.crawledAt = crawledAt; }
}
