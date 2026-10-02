package dev.ratelimiter.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ClientTierResolverTest {

    private static final String CACHE_KEY_PREFIX = "api-gateway:rate-limit:tier:";

    private ClientCredentialRepository repository;
    private ReactiveValueOperations<String, String> valueOperations;
    private ClientTierResolver resolver;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(ClientCredentialRepository.class);
        ReactiveStringRedisTemplate redisTemplate = mock(ReactiveStringRedisTemplate.class);
        valueOperations = mock(ReactiveValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        RateLimitProperties properties = new RateLimitProperties();
        properties.getDefaultLimit().setCapacity(10);
        properties.getDefaultLimit().setRefillTokens(10);
        properties.getDefaultLimit().setRefillPeriod(Duration.ofSeconds(1));
        properties.setTierCacheTtl(Duration.ofSeconds(30));

        resolver = new ClientTierResolver(repository, redisTemplate, properties);
    }

    @Test
    void returnsCachedLimitWithoutHittingDatabase() {
        when(valueOperations.get(CACHE_KEY_PREFIX + "acme")).thenReturn(Mono.just("20:20:2000"));

        StepVerifier.create(resolver.resolveLimit("acme"))
                .assertNext(limit -> {
                    assertThat(limit.getCapacity()).isEqualTo(20);
                    assertThat(limit.getRefillTokens()).isEqualTo(20);
                    assertThat(limit.getRefillPeriod()).isEqualTo(Duration.ofMillis(2000));
                })
                .verifyComplete();

        verifyNoInteractions(repository);
    }

    @Test
    void loadsFromDatabaseOnCacheMissAndPopulatesCache() {
        when(valueOperations.get(CACHE_KEY_PREFIX + "acme")).thenReturn(Mono.empty());
        when(valueOperations.set(eq(CACHE_KEY_PREFIX + "acme"), eq("15:15:1000"), any(Duration.class)))
                .thenReturn(Mono.just(true));
        ClientCredential credential =
                new ClientCredential("acme", "hash", 15, 15, 1000, true, Instant.now(), Instant.now());
        when(repository.findById("acme")).thenReturn(Mono.just(credential));

        StepVerifier.create(resolver.resolveLimit("acme"))
                .assertNext(limit -> {
                    assertThat(limit.getCapacity()).isEqualTo(15);
                    assertThat(limit.getRefillTokens()).isEqualTo(15);
                    assertThat(limit.getRefillPeriod()).isEqualTo(Duration.ofMillis(1000));
                })
                .verifyComplete();

        verify(valueOperations).set(eq(CACHE_KEY_PREFIX + "acme"), eq("15:15:1000"), any(Duration.class));
    }

    @Test
    void fallsBackToStaticDefaultWhenClientHasNoDatabaseRow() {
        when(valueOperations.get(CACHE_KEY_PREFIX + "unknown")).thenReturn(Mono.empty());
        when(repository.findById("unknown")).thenReturn(Mono.empty());

        StepVerifier.create(resolver.resolveLimit("unknown"))
                .assertNext(limit -> assertThat(limit.getCapacity()).isEqualTo(10))
                .verifyComplete();
    }

    @Test
    void fallsBackToStaticDefaultWhenClientIsDisabled() {
        when(valueOperations.get(CACHE_KEY_PREFIX + "disabled-client")).thenReturn(Mono.empty());
        ClientCredential credential =
                new ClientCredential("disabled-client", "hash", 99, 99, 1000, false, Instant.now(), Instant.now());
        when(repository.findById("disabled-client")).thenReturn(Mono.just(credential));

        StepVerifier.create(resolver.resolveLimit("disabled-client"))
                .assertNext(limit -> assertThat(limit.getCapacity()).isEqualTo(10))
                .verifyComplete();
    }

    @Test
    void fallsBackToStaticDefaultWhenRedisAndDatabaseAreUnavailable() {
        when(valueOperations.get(CACHE_KEY_PREFIX + "acme")).thenReturn(Mono.error(new RuntimeException("redis down")));
        when(repository.findById("acme")).thenReturn(Mono.error(new RuntimeException("db down")));

        StepVerifier.create(resolver.resolveLimit("acme"))
                .assertNext(limit -> assertThat(limit.getCapacity()).isEqualTo(10))
                .verifyComplete();
    }

    @Test
    void invalidateDeletesCacheKey() {
        when(valueOperations.delete(CACHE_KEY_PREFIX + "acme")).thenReturn(Mono.just(true));

        StepVerifier.create(resolver.invalidate("acme")).verifyComplete();

        verify(valueOperations).delete(CACHE_KEY_PREFIX + "acme");
    }
}
