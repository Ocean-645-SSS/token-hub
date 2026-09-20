package com.tokenhub.service;
import com.tokenhub.common.BusinessException;
import com.tokenhub.config.RateLimitProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.util.Collections;
import org.springframework.data.redis.core.script.DefaultRedisScript;
@Service public class ApiKeyRateLimiter {
 private final StringRedisTemplate redis; private final RateLimitProperties properties; private final DefaultRedisScript<Long> script=new DefaultRedisScript<>("local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('PEXPIRE',KEYS[1],1000) end; if n>tonumber(ARGV[1]) then return 0 end return 1",Long.class);
 public ApiKeyRateLimiter(StringRedisTemplate redis,RateLimitProperties properties){this.redis=redis;this.properties=properties;}
 public void check(long keyId){try{Long ok=redis.execute(script,Collections.singletonList("ratelimit:apikey:"+keyId),String.valueOf(properties.getApiKeyQps()));if(ok==null||ok==0)throw new BusinessException("RATE_LIMIT_EXCEEDED","api key rate limit exceeded",HttpStatus.TOO_MANY_REQUESTS);}catch(BusinessException e){throw e;}catch(Exception e){throw new BusinessException("RATE_LIMIT_EXCEEDED","rate limiter unavailable",HttpStatus.TOO_MANY_REQUESTS);}}
}
