package com.hrms.recruitment.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Optional;

/** Redis atomic counter (INCR) -> base62 short code. Mapping stored as Redis keys. */
@Service
public class UrlShortenerService {
    private static final String ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final long OFFSET = 100000; // makes codes at least 3 chars
    private final StringRedisTemplate redis;
    private final String baseUrl;

    public UrlShortenerService(StringRedisTemplate redis, @Value("${app.base-url}") String baseUrl) {
        this.redis = redis;
        this.baseUrl = baseUrl;
    }

    public String shorten(String longUrl) {
        Long id = redis.opsForValue().increment("url:counter");
        String code = encode(id + OFFSET);
        redis.opsForValue().set("url:" + code, longUrl);
        return code;
    }

    public Optional<String> resolve(String code) {
        String url = redis.opsForValue().get("url:" + code);
        if (url != null) redis.opsForValue().increment("url:clicks:" + code);
        return Optional.ofNullable(url);
    }

    public long clicks(String code) {
        String c = redis.opsForValue().get("url:clicks:" + code);
        return c == null ? 0 : Long.parseLong(c);
    }

    public String fullShortUrl(String code) { return baseUrl + "/s/" + code; }

    static String encode(long n) {
        StringBuilder sb = new StringBuilder();
        while (n > 0) { sb.append(ALPHABET.charAt((int) (n % 62))); n /= 62; }
        return sb.reverse().toString();
    }
}
