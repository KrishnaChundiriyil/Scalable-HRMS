package com.scalablehrms.leave.repo;

import com.scalablehrms.leave.domain.LeaveAccount;
import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface LeaveAccountRepository extends MongoRepository<LeaveAccount, String> {
    List<LeaveAccount> findByRequestsStatus(com.scalablehrms.leave.domain.LeaveStatus status);
}
