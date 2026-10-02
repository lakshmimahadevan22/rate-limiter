package dev.ratelimiter.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class RateLimitPropertiesTest {

    @Test
    void limitForReturnsClientOverrideWhenConfigured() {
        RateLimitProperties properties = new RateLimitProperties();
        RateLimitProperties.Limit override = new RateLimitProperties.Limit();
        override.setCapacity(50);
        override.setRefillTokens(50);
        override.setRefillPeriod(Duration.ofMillis(500));
        properties.getClientLimits().put("acme", override);

        assertThat(properties.limitFor("acme")).isSameAs(override);
    }

    @Test
    void limitForReturnsDefaultWhenNoOverrideConfigured() {
        RateLimitProperties properties = new RateLimitProperties();

        assertThat(properties.limitFor("unknown-client")).isSameAs(properties.getDefaultLimit());
    }

    @Test
    void validatePassesForDefaultConfiguration() {
        RateLimitProperties properties = new RateLimitProperties();

        assertThatCode(properties::validate).doesNotThrowAnyException();
    }

    @Test
    void validateRejectsBlankKeyPrefix() {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setKeyPrefix(" ");

        assertThatThrownBy(properties::validate).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validateRejectsNonPositiveTierCacheTtl() {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setTierCacheTtl(Duration.ZERO);

        assertThatThrownBy(properties::validate).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validateRejectsNonPositiveCapacityOnDefaultLimit() {
        RateLimitProperties properties = new RateLimitProperties();
        properties.getDefaultLimit().setCapacity(0);

        assertThatThrownBy(properties::validate).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validateRejectsBlankClientIdInClientLimits() {
        RateLimitProperties properties = new RateLimitProperties();
        properties.getClientLimits().put(" ", new RateLimitProperties.Limit());

        assertThatThrownBy(properties::validate).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validateRejectsNonPositiveRefillPeriodOnClientOverride() {
        RateLimitProperties properties = new RateLimitProperties();
        RateLimitProperties.Limit override = new RateLimitProperties.Limit();
        override.setRefillPeriod(Duration.ZERO);
        properties.getClientLimits().put("acme", override);

        assertThatThrownBy(properties::validate).isInstanceOf(IllegalArgumentException.class);
    }
}
