package com.scalablehrms.leave.service;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
public class RedisRateLimiter {
    // TIME is evaluated on Redis; token update and decision happen atomically in one script.
    private static final String LUA = """
        local time = redis.call('TIME')
        local now = tonumber(time[1]) + tonumber(time[2]) / 1000000
        local capacity = tonumber(ARGV[1])
        local refill = tonumber(ARGV[2])
        local data = redis.call('HMGET', KEYS[1], 'tokens', 'updatedAt')
        local tokens = tonumber(data[1]) or capacity
        local previous = tonumber(data[2]) or now
        tokens = math.min(capacity, tokens + math.max(0, now - previous) * refill)
        local allowed = 0
        if tokens >= 1 then
           tokens = tokens - 1
           allowed = 1
        end
        redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'updatedAt', tostring(now))
        redis.call('EXPIRE', KEYS[1], 86400)
        return allowed
        """;
    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> script = new DefaultRedisScript<>(LUA, Long.class);
    private final int capacity;
    private final int refill;

    public RedisRateLimiter(StringRedisTemplate redis,
                            @Value("${hrms.rate.capacity:5}") int capacity,
                            @Value("${hrms.rate.refill-per-second:1}") int refill) {
        this.redis = redis; this.capacity=capacity; this.refill=refill;
    }
    public boolean allow(String verifiedEmployeeId) {
        Long allowed = redis.execute(script, List.of("hrms:leave:bucket:" + verifiedEmployeeId),
                                     Integer.toString(capacity), Integer.toString(refill));
        return Long.valueOf(1).equals(allowed);
    }
}
