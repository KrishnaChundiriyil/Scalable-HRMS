package com.hrms.recruitment.repo;

import com.hrms.recruitment.model.Candidate;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface CandidateRepository extends MongoRepository<Candidate, String> {
    java.util.Optional<Candidate> findByEmail(String email);
}
