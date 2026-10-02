package dev.ratelimiter.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@SuppressWarnings("unchecked")
class RateLimitingFilterTest {

    private ReactiveStringRedisTemplate redisTemplate;
    private RedisScript<String> tokenBucketScript;
    private ClientTierResolver tierResolver;
    private RateLimitProperties properties;
    private RateLimitingFilter filter;
    private GatewayFilterChain chain;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(ReactiveStringRedisTemplate.class);
        tokenBucketScript = mock(RedisScript.class);
        tierResolver = mock(ClientTierResolver.class);

        properties = new RateLimitProperties();
        properties.getDefaultLimit().setCapacity(10);
        properties.getDefaultLimit().setRefillTokens(10);
        properties.getDefaultLimit().setRefillPeriod(Duration.ofSeconds(1));

        chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());

        filter = new RateLimitingFilter(redisTemplate, tokenBucketScript, properties, tierResolver);
    }

    private static ServerWebExchange exchangeFor(String clientId) {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/demo/hello").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        if (clientId == null) {
            return exchange;
        }
        TestingAuthenticationToken authentication = new TestingAuthenticationToken(clientId, null, List.of());
        authentication.setAuthenticated(true);
        return exchange.mutate().principal(Mono.just(authentication)).build();
    }

    private RateLimitProperties.Limit limit(int capacity, int refillTokens, Duration refillPeriod) {
        RateLimitProperties.Limit result = new RateLimitProperties.Limit();
        result.setCapacity(capacity);
        result.setRefillTokens(refillTokens);
        result.setRefillPeriod(refillPeriod);
        return result;
    }

    @Test
    void forwardsRequestAndSetsHeadersWhenTokensAreAvailable() {
        when(tierResolver.resolveLimit("acme")).thenReturn(Mono.just(limit(10, 10, Duration.ofSeconds(1))));
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyList()))
                .thenReturn(Flux.just("1:9:0:10"));

        ServerWebExchange exchange = exchangeFor("acme");

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(chain).filter(exchange);
        assertThat(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Limit")).isEqualTo("10");
        assertThat(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Remaining")).isEqualTo("9");
    }

    @Test
    void returnsTooManyRequestsWithRetryAfterWhenTokensAreExhausted() {
        when(tierResolver.resolveLimit("acme")).thenReturn(Mono.just(limit(5, 5, Duration.ofSeconds(1))));
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyList()))
                .thenReturn(Flux.just("0:0:2500:5"));

        ServerWebExchange exchange = exchangeFor("acme");

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(chain, never()).filter(any());
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("3");
        assertThat(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Limit")).isEqualTo("5");
        assertThat(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Remaining")).isEqualTo("0");
    }

    @Test
    void returnsServiceUnavailableWhenRedisFails() {
        when(tierResolver.resolveLimit("acme")).thenReturn(Mono.just(limit(10, 10, Duration.ofSeconds(1))));
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyList()))
                .thenReturn(Flux.error(new RuntimeException("redis unavailable")));

        ServerWebExchange exchange = exchangeFor("acme");

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(chain, never()).filter(any());
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void returnsUnauthorizedWhenNoPrincipalIsPresent() {
        ServerWebExchange exchange = exchangeFor(null);

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(chain, never()).filter(any());
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(tierResolver, redisTemplate);
    }

    @Test
    void bypassesRateLimitingWhenDisabled() {
        properties.setEnabled(false);
        ServerWebExchange exchange = exchangeFor("acme");

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(chain).filter(exchange);
        verifyNoInteractions(tierResolver, redisTemplate);
    }
}
