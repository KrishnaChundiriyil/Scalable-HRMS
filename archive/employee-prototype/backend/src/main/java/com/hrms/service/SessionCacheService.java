package com.hrms.service;

import com.hrms.model.UserAccount;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/** Caches lightweight session metadata; JWT authentication remains stateless. */
@Service
public class SessionCacheService {
    private final StringRedisTemplate redis;
    public SessionCacheService(StringRedisTemplate redis) { this.redis = redis; }
    public void cacheLogin(UserAccount user) {
        String key = "hrms:session:" + user.getEmail().toLowerCase();
        redis.opsForHash().put(key, "email", user.getEmail());
        redis.opsForHash().put(key, "employeeId", user.getEmployeeId());
        redis.opsForHash().put(key, "role", user.getRole().name());
        redis.opsForHash().put(key, "lastLogin", Instant.now().toString());
        redis.expire(key, Duration.ofHours(8));
    }
}
