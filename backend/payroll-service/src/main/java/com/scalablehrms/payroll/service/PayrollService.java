package com.scalablehrms.payroll.service;

import com.scalablehrms.payroll.domain.PayrollRecord;
import com.scalablehrms.payroll.dto.PayrollCalculation;
import com.scalablehrms.payroll.dto.PayrollRequest;
import com.scalablehrms.payroll.repository.PayrollRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class PayrollService {
    private final PayrollRepository repository;

    public PayrollService(PayrollRepository repository) {
        this.repository = repository;
    }

    public PayrollCalculation calculate(PayrollRequest request) {
        BigDecimal basic = money(request.basicSalary());
        BigDecimal allowances = money(request.allowances());
        BigDecimal deductions = money(request.deductions());
        BigDecimal gross = basic.add(allowances).setScale(2, RoundingMode.HALF_UP);
        BigDecimal net = gross.subtract(deductions).setScale(2, RoundingMode.HALF_UP);
        if (net.signum() < 0) {
            throw new IllegalArgumentException("Deductions cannot exceed gross salary.");
        }
        return new PayrollCalculation(basic, allowances, deductions, gross, net);
    }

    public PayrollRecord create(PayrollRequest request) {
        repository.findByEmployeeIdAndYearAndMonth(request.employeeId(), request.year(), request.month())
                .ifPresent(existing -> {
                    throw new PayrollConflictException("Payroll already exists for this employee and month.");
                });

        PayrollCalculation result = calculate(request);
        PayrollRecord record = new PayrollRecord(
                request.employeeId(), request.year(), request.month(),
                result.basicSalary(), result.allowances(), result.deductions(),
                result.grossSalary(), result.netSalary());
        // Same employee/period always routes to the same shard and document ID.
        // Insert (never upsert) makes competing instances reject duplicates.
        record.setId(request.employeeId()+":"+request.year()+"-"+String.format(java.util.Locale.ROOT,"%02d",request.month()));
        return repository.insert(record);
    }

    public PayrollRecord getById(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new PayrollNotFoundException("Payroll record not found: " + id));
    }

    public List<PayrollRecord> history(String employeeId) {
        return repository.findByEmployeeIdOrderByYearDescMonthDesc(employeeId);
    }

    public PayrollRecord getForPeriod(String employeeId, int year, int month) {
        return repository.findByEmployeeIdAndYearAndMonth(employeeId, year, month)
                .orElseThrow(() -> new PayrollNotFoundException(
                        "No payroll found for employee " + employeeId + " for " + year + "-" + month));
    }

    private BigDecimal money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }
}
