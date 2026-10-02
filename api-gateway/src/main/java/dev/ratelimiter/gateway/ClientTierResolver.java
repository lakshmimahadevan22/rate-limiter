package dev.ratelimiter.gateway;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

/**
 * Resolves the effective rate-limit tier for a client, preferring a database-configured
 * tier (cached in Redis) and falling back to static YAML configuration whenever Redis or
 * Postgres are unavailable or the client has no dynamic tier configured.
 */
@Component
public class ClientTierResolver {

    private static final Logger logger = LoggerFactory.getLogger(ClientTierResolver.class);

    private final ClientCredentialRepository repository;
    private final ReactiveStringRedisTemplate redisTemplate;
    private final RateLimitProperties properties;

    public ClientTierResolver(
            ClientCredentialRepository repository,
            ReactiveStringRedisTemplate redisTemplate,
            RateLimitProperties properties) {
        this.repository = repository;
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    public Mono<RateLimitProperties.Limit> resolveLimit(String clientId) {
        return readFromCache(clientId)
                .switchIfEmpty(Mono.defer(() -> readFromDatabaseAndCache(clientId)))
                .switchIfEmpty(Mono.fromSupplier(() -> properties.limitFor(clientId)))
                .onErrorResume(exception -> {
                    logger.warn("Falling back to static rate-limit configuration for clientId={}", clientId, exception);
                    return Mono.just(properties.limitFor(clientId));
                });
    }

    public Mono<Void> invalidate(String clientId) {
        return Mono.defer(() -> redisTemplate.opsForValue().delete(cacheKey(clientId)))
                .onErrorResume(exception -> {
                    logger.debug("Rate-limit tier cache eviction failed for clientId={}", clientId, exception);
                    return Mono.just(false);
                })
                .then();
    }

    private Mono<RateLimitProperties.Limit> readFromCache(String clientId) {
        return Mono.defer(() -> redisTemplate.opsForValue().get(cacheKey(clientId)))
                .mapNotNull(ClientTierResolver::deserialize)
                .onErrorResume(exception -> {
                    logger.debug("Rate-limit tier cache read failed for clientId={}", clientId, exception);
                    return Mono.empty();
                });
    }

    private Mono<RateLimitProperties.Limit> readFromDatabaseAndCache(String clientId) {
        return repository.findById(clientId)
                .filter(ClientCredential::isEnabled)
                .map(ClientTierResolver::toLimit)
                .flatMap(limit -> writeToCache(clientId, limit).thenReturn(limit))
                .onErrorResume(exception -> {
                    logger.debug("Rate-limit tier database lookup failed for clientId={}", clientId, exception);
                    return Mono.empty();
                });
    }

    private Mono<Void> writeToCache(String clientId, RateLimitProperties.Limit limit) {
        return Mono.defer(() -> redisTemplate.opsForValue()
                        .set(cacheKey(clientId), serialize(limit), properties.getTierCacheTtl()))
                .onErrorResume(exception -> {
                    logger.debug("Rate-limit tier cache write failed for clientId={}", clientId, exception);
                    return Mono.just(false);
                })
                .then();
    }

    private String cacheKey(String clientId) {
        return properties.getKeyPrefix() + "tier:" + clientId;
    }

    private static RateLimitProperties.Limit toLimit(ClientCredential credential) {
        RateLimitProperties.Limit limit = new RateLimitProperties.Limit();
        limit.setCapacity(credential.getRateLimitCapacity());
        limit.setRefillTokens(credential.getRateLimitRefillTokens());
        limit.setRefillPeriod(Duration.ofMillis(credential.getRateLimitRefillPeriodMillis()));
        return limit;
    }

    private static String serialize(RateLimitProperties.Limit limit) {
        return limit.getCapacity() + ":" + limit.getRefillTokens() + ":" + limit.getRefillPeriod().toMillis();
    }

    private static RateLimitProperties.Limit deserialize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String[] parts = value.split(":", -1);
        if (parts.length != 3) {
            return null;
        }
        RateLimitProperties.Limit limit = new RateLimitProperties.Limit();
        limit.setCapacity(Integer.parseInt(parts[0]));
        limit.setRefillTokens(Integer.parseInt(parts[1]));
        limit.setRefillPeriod(Duration.ofMillis(Long.parseLong(parts[2])));
        return limit;
    }
}
