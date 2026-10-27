package com.hrms.recruitment.repo;

import com.hrms.recruitment.model.JobApplication;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface JobApplicationRepository extends MongoRepository<JobApplication, String> {
    java.util.List<JobApplication> findByApplicantAccountId(String applicantAccountId);
    java.util.List<JobApplication> findByJobId(String jobId);
}
