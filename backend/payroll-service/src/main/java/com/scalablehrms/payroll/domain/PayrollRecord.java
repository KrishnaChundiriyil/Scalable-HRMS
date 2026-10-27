package com.scalablehrms.payroll.domain;

import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "payroll_records")
@CompoundIndex(name = "employee_period_idx", def = "{'employeeId': 1, 'year': -1, 'month': -1}")
public class PayrollRecord {
    @Id
    private String id;
    private String employeeId;
    private int year;
    private int month;
    private BigDecimal basicSalary;
    private BigDecimal allowances;
    private BigDecimal deductions;
    private BigDecimal grossSalary;
    private BigDecimal netSalary;
    private Instant createdAt;

    public PayrollRecord() {}

    public PayrollRecord(String employeeId, int year, int month,
                         BigDecimal basicSalary, BigDecimal allowances,
                         BigDecimal deductions, BigDecimal grossSalary,
                         BigDecimal netSalary) {
        this.employeeId = employeeId;
        this.year = year;
        this.month = month;
        this.basicSalary = basicSalary;
        this.allowances = allowances;
        this.deductions = deductions;
        this.grossSalary = grossSalary;
        this.netSalary = netSalary;
        this.createdAt = Instant.now();
    }

    public String getId() { return id; }
    public String getEmployeeId() { return employeeId; }
    public int getYear() { return year; }
    public int getMonth() { return month; }
    public BigDecimal getBasicSalary() { return basicSalary; }
    public BigDecimal getAllowances() { return allowances; }
    public BigDecimal getDeductions() { return deductions; }
    public BigDecimal getGrossSalary() { return grossSalary; }
    public BigDecimal getNetSalary() { return netSalary; }
    public Instant getCreatedAt() { return createdAt; }

    public void setId(String id) { this.id = id; }
    public void setEmployeeId(String employeeId) { this.employeeId = employeeId; }
    public void setYear(int year) { this.year = year; }
    public void setMonth(int month) { this.month = month; }
    public void setBasicSalary(BigDecimal basicSalary) { this.basicSalary = basicSalary; }
    public void setAllowances(BigDecimal allowances) { this.allowances = allowances; }
    public void setDeductions(BigDecimal deductions) { this.deductions = deductions; }
    public void setGrossSalary(BigDecimal grossSalary) { this.grossSalary = grossSalary; }
    public void setNetSalary(BigDecimal netSalary) { this.netSalary = netSalary; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
