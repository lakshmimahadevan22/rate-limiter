package dev.ratelimiter.gateway;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

@Component
public class RateLimitingFilter implements GlobalFilter, Ordered {

    public static final String RATE_LIMIT_LIMIT_HEADER = "X-RateLimit-Limit";
    public static final String RATE_LIMIT_REMAINING_HEADER = "X-RateLimit-Remaining";

    private static final Logger logger = LoggerFactory.getLogger(RateLimitingFilter.class);

    private final ReactiveStringRedisTemplate redisTemplate;
    private final RedisScript<String> tokenBucketScript;
    private final RateLimitProperties properties;
    private final ClientTierResolver tierResolver;

    public RateLimitingFilter(
            ReactiveStringRedisTemplate redisTemplate,
            RedisScript<String> tokenBucketScript,
            RateLimitProperties properties,
            ClientTierResolver tierResolver) {
        this.redisTemplate = redisTemplate;
        this.tokenBucketScript = tokenBucketScript;
        this.properties = properties;
        this.tierResolver = tierResolver;
        properties.validate();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!properties.isEnabled()) {
            return chain.filter(exchange);
        }

        return exchange.getPrincipal()
                .ofType(Authentication.class)
                .map(Authentication::getName)
                .flatMap(clientId -> consume(clientId)
                        .flatMap(decision -> applyDecision(exchange, chain, clientId, decision)))
                .switchIfEmpty(Mono.defer(() -> complete(exchange, HttpStatus.UNAUTHORIZED)));
    }

    private Mono<Decision> consume(String clientId) {
        return tierResolver.resolveLimit(clientId)
                .flatMap(limit -> {
                    List<String> keys = List.of(properties.getKeyPrefix() + clientId);
                    List<String> arguments = List.of(
                            Integer.toString(limit.getCapacity()),
                            Integer.toString(limit.getRefillTokens()),
                            Long.toString(limit.getRefillPeriod().toMillis()),
                            "1");

                    return redisTemplate.execute(tokenBucketScript, keys, arguments)
                            .next()
                            .switchIfEmpty(Mono.error(
                                    new IllegalStateException("Redis token-bucket script returned no result")))
                            .map(RateLimitingFilter::parseDecision);
                })
                .onErrorResume(exception -> {
                    logger.error("Rate-limit check failed for clientId={}", clientId, exception);
                    return Mono.just(Decision.unavailable());
                });
    }

    private Mono<Void> applyDecision(
            ServerWebExchange exchange,
            GatewayFilterChain chain,
            String clientId,
            Decision decision) {
        if (!decision.available()) {
            return complete(exchange, HttpStatus.SERVICE_UNAVAILABLE);
        }

        exchange.getResponse().getHeaders().set(RATE_LIMIT_LIMIT_HEADER, Integer.toString(decision.limit()));
        exchange.getResponse().getHeaders().set(
                RATE_LIMIT_REMAINING_HEADER,
                Integer.toString(decision.remaining()));

        if (!decision.allowed()) {
            long retryAfterSeconds = Math.max(1, (decision.retryAfterMillis() + 999) / 1000);
            exchange.getResponse().getHeaders().set("Retry-After", Long.toString(retryAfterSeconds));
            logger.info("Request rate limited clientId={} retryAfterSeconds={}", clientId, retryAfterSeconds);
            return complete(exchange, HttpStatus.TOO_MANY_REQUESTS);
        }

        return chain.filter(exchange);
    }

    private static Mono<Void> complete(ServerWebExchange exchange, HttpStatus status) {
        exchange.getResponse().setStatusCode(status);
        return exchange.getResponse().setComplete();
    }

    private static Decision parseDecision(String result) {
        String[] values = result.split(":", -1);
        if (values.length != 4) {
            throw new IllegalArgumentException("Invalid Redis token-bucket response");
        }
        return new Decision(
                true,
                "1".equals(values[0]),
                Integer.parseInt(values[1]),
                Long.parseLong(values[2]),
                Integer.parseInt(values[3]));
    }

    @Override
    public int getOrder() {
        return -1;
    }

    private record Decision(boolean available, boolean allowed, int remaining, long retryAfterMillis, int limit) {

        private static Decision unavailable() {
            return new Decision(false, false, 0, 0, 0);
        }
    }
}