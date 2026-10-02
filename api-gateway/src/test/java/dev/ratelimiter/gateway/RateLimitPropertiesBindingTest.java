package dev.ratelimiter.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Verifies that {@link RateLimitProperties} binds relaxed, kebab-case YAML/env keys
 * (including nested maps and ISO-8601 durations) the way {@code application.yml} relies on.
 */
class RateLimitPropertiesBindingTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(TestConfiguration.class);

    @Test
    void bindsNestedClientLimitsAndDurationsFromProperties() {
        contextRunner
                .withPropertyValues(
                        "gateway.rate-limit.enabled=true",
                        "gateway.rate-limit.key-prefix=custom:",
                        "gateway.rate-limit.default-limit.capacity=7",
                        "gateway.rate-limit.default-limit.refill-tokens=7",
                        "gateway.rate-limit.default-limit.refill-period=PT2S",
                        "gateway.rate-limit.tier-cache-ttl=PT45S",
                        "gateway.rate-limit.client-limits.acme.capacity=50",
                        "gateway.rate-limit.client-limits.acme.refill-tokens=50",
                        "gateway.rate-limit.client-limits.acme.refill-period=500ms")
                .run(context -> {
                    RateLimitProperties properties = context.getBean(RateLimitProperties.class);

                    assertThat(properties.isEnabled()).isTrue();
                    assertThat(properties.getKeyPrefix()).isEqualTo("custom:");
                    assertThat(properties.getDefaultLimit().getCapacity()).isEqualTo(7);
                    assertThat(properties.getDefaultLimit().getRefillPeriod()).isEqualTo(Duration.ofSeconds(2));
                    assertThat(properties.getTierCacheTtl()).isEqualTo(Duration.ofSeconds(45));

                    RateLimitProperties.Limit acme = properties.limitFor("acme");
                    assertThat(acme.getCapacity()).isEqualTo(50);
                    assertThat(acme.getRefillPeriod()).isEqualTo(Duration.ofMillis(500));
                });
    }

    @Test
    void appliesDefaultsWhenNoPropertiesAreSupplied() {
        contextRunner.run(context -> {
            RateLimitProperties properties = context.getBean(RateLimitProperties.class);

            assertThat(properties.isEnabled()).isTrue();
            assertThat(properties.getDefaultLimit().getCapacity()).isEqualTo(10);
            assertThat(properties.getTierCacheTtl()).isEqualTo(Duration.ofSeconds(30));
        });
    }

    @EnableConfigurationProperties(RateLimitProperties.class)
    static class TestConfiguration {
    }
}
