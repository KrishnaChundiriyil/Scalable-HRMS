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
        local retry = 0
        if tokens >= 1 then
           tokens = tokens - 1
        else
           retry = math.ceil((1 - tokens) / refill)
        end
        redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'updatedAt', tostring(now))
        redis.call('EXPIRE', KEYS[1], math.max(60, math.ceil(capacity / refill) * 2))
        return retry
        """;
    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> script = new DefaultRedisScript<>(LUA, Long.class);
    private final int capacity;
    private final double refill;

    public RedisRateLimiter(StringRedisTemplate redis,
                            @Value("${hrms.rate.capacity:5}") int capacity,
                            @Value("${hrms.rate.refill-per-second:1}") double refill) {
        if (capacity < 1 || !Double.isFinite(refill) || refill <= 0)
            throw new IllegalArgumentException("Rate limit capacity and refill must be positive");
        this.redis = redis; this.capacity=capacity; this.refill=refill;
    }
    public long retryAfterSeconds(String verifiedEmployeeId) {
        Long retry = redis.execute(script, List.of("hrms:leave:bucket:" + verifiedEmployeeId),
                                     Integer.toString(capacity), Double.toString(refill));
        if (retry == null) throw new IllegalStateException("No Redis rate-limit response");
        return retry;
    }
}
