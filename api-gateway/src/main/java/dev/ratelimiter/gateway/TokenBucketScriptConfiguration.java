package dev.ratelimiter.gateway;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.RedisScript;

@Configuration
public class TokenBucketScriptConfiguration {

    @Bean
    RedisScript<String> tokenBucketRedisScript() {
        return RedisScript.of(new ClassPathResource("scripts/token-bucket.lua"), String.class);
    }
}