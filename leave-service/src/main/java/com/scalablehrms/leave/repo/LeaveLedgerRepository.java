package com.scalablehrms.leave.repo;

import java.util.List;
import com.scalablehrms.leave.domain.LeaveLedger;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface LeaveLedgerRepository extends MongoRepository<LeaveLedger, String> {
    List<LeaveLedger> findByEmployeeId(String employeeId);
    List<LeaveLedger> findByYear(int year);
}
