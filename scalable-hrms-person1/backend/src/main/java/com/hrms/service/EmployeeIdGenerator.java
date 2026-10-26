package com.hrms.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class EmployeeIdGenerator {
    private static final String COUNTER_KEY = "hrms:employee:id-counter";
    private final StringRedisTemplate redis;

    public EmployeeIdGenerator(StringRedisTemplate redis) { this.redis = redis; }

    public String nextId() {
        Long value = redis.opsForValue().increment(COUNTER_KEY);
        if (value == null) throw new IllegalStateException("Could not generate employee ID");
        return "EMP" + (1000 + value);
    }
}
