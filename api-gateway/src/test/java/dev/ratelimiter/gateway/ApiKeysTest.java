package dev.ratelimiter.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ApiKeysTest {

    @Test
    void generatesDistinctUrlSafeKeys() {
        String first = ApiKeys.generate();
        String second = ApiKeys.generate();

        assertThat(first).isNotEqualTo(second).matches("[A-Za-z0-9_-]{43}");
    }

    @Test
    void matchesOnlyTheKeyThatWasHashed() {
        String key = ApiKeys.generate();
        String storedHash = ApiKeys.hash(key);

        assertThat(storedHash).isNotEqualTo(key);
        assertThat(ApiKeys.matches(key, storedHash)).isTrue();
        assertThat(ApiKeys.matches(ApiKeys.generate(), storedHash)).isFalse();
        assertThat(ApiKeys.matches(null, storedHash)).isFalse();
    }
}
