package dev.ratelimiter.gateway;

import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.TimeoutOptions;

/**
 * Makes Redis outages fail fast. By default Lettuce queues commands while disconnected and waits
 * for a reconnect, so with Redis down every rate-limited request would hang instead of getting
 * the gateway's fail-closed {@code 503}. Rejecting commands while disconnected covers a stopped
 * Redis; the command timeout ({@code spring.data.redis.timeout}) covers one that stops responding
 * without closing the connection.
 */
@Configuration
public class RedisClientConfiguration {

    @Bean
    LettuceClientConfigurationBuilderCustomizer failFastRedisClient() {
        return builder -> builder.clientOptions(ClientOptions.builder()
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .timeoutOptions(TimeoutOptions.enabled())
                .build());
    }
}
