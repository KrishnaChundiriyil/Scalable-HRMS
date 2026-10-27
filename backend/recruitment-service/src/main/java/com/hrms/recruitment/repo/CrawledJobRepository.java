package com.hrms.recruitment.repo;

import com.hrms.recruitment.model.CrawledJob;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface CrawledJobRepository extends MongoRepository<CrawledJob, String> {
    boolean existsBySourceUrl(String sourceUrl);
}
