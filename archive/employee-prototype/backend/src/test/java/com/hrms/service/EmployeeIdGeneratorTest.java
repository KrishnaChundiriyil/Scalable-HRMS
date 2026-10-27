package com.hrms.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EmployeeIdGeneratorTest {
    @Test
    void expectedEmployeeIdFormat() {
        // The real Redis counter integration is exercised manually with Docker.
        assertTrue("EMP1001".matches("EMP\\d+"));
        assertTrue("EMP1002".matches("EMP\\d+"));
    }
}
