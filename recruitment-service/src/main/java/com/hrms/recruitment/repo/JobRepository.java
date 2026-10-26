package com.hrms.recruitment.repo;

import com.hrms.recruitment.model.Job;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface JobRepository extends MongoRepository<Job, String> {
    java.util.Optional<Job> findByShortCode(String shortCode);
}
