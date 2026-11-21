package com.tokenhub.service;
import com.tokenhub.common.BusinessException;
import com.tokenhub.config.RateLimitProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.util.Collections;
import org.springframework.data.redis.core.script.DefaultRedisScript;
@Service public class ApiKeyRateLimiter {
    // Store one token as 1000 units so refill can use integer milliseconds.
    private static final DefaultRedisScript<Long> TOKEN_BUCKET = new DefaultRedisScript<>("""
            local rate = tonumber(ARGV[1])
            if rate <= 0 then return 0 end
            local capacity = rate * 1000
            local time = redis.call('TIME')
            local serverTime = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
            local state = redis.call('HMGET', KEYS[1], 'balance', 'updated_at')
            local previous = tonumber(state[2]) or serverTime
            local now = math.max(serverTime, previous)
            local elapsed = math.min(1000, now - previous)
            local balance = math.min(capacity, (tonumber(state[1]) or capacity) + elapsed * rate)
            if balance < 1000 then return 0 end
            redis.call('HSET', KEYS[1], 'balance', balance - 1000, 'updated_at', now)
            redis.call('PEXPIRE', KEYS[1], 1000 + now - serverTime)
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final RateLimitProperties properties;

    public ApiKeyRateLimiter(StringRedisTemplate redis, RateLimitProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    public void check(long keyId) {
        Long allowed;
        try {
            allowed = redis.execute(TOKEN_BUCKET, Collections.singletonList("ratelimit:apikey:bucket:" + keyId),
                    String.valueOf(properties.getApiKeyQps()));
        } catch (Exception e) {
            throw new BusinessException("RATE_LIMIT_EXCEEDED", "rate limiter unavailable", HttpStatus.TOO_MANY_REQUESTS);
        }
        if (allowed == null || allowed != 1L) {
            throw new BusinessException("RATE_LIMIT_EXCEEDED", "api key rate limit exceeded", HttpStatus.TOO_MANY_REQUESTS);
        }
    }
}
