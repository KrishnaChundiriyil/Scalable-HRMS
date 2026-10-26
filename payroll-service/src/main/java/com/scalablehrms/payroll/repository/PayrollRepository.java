package com.scalablehrms.payroll.repository;

import com.scalablehrms.payroll.domain.PayrollRecord;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface PayrollRepository extends MongoRepository<PayrollRecord, String> {
    List<PayrollRecord> findByEmployeeIdOrderByYearDescMonthDesc(String employeeId);
    Optional<PayrollRecord> findByEmployeeIdAndYearAndMonth(String employeeId, int year, int month);
}
