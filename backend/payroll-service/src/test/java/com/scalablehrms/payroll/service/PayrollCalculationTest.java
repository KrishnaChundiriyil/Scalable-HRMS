package com.scalablehrms.payroll.service;

import static org.junit.jupiter.api.Assertions.*;
import com.scalablehrms.payroll.dto.PayrollRequest;
import com.scalablehrms.payroll.dto.PayrollCalculation;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

class PayrollCalculationTest {
    @Test
    void calculatesGrossAndNetSalary() {
        PayrollService service = new PayrollService(null);
        PayrollCalculation result = service.calculate(new PayrollRequest(
                "EMP001", 2026, 10,
                new BigDecimal("30000"), new BigDecimal("5000"), new BigDecimal("2000")));
        assertEquals(new BigDecimal("35000.00"), result.grossSalary());
        assertEquals(new BigDecimal("33000.00"), result.netSalary());
    }

    @Test
    void rejectsDeductionsGreaterThanGross() {
        PayrollService service = new PayrollService(null);
        assertThrows(IllegalArgumentException.class, () -> service.calculate(new PayrollRequest(
                "EMP001", 2026, 10,
                new BigDecimal("1000"), new BigDecimal("0"), new BigDecimal("1500"))));
    }
}
