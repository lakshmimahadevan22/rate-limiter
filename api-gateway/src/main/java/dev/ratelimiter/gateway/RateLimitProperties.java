package dev.ratelimiter.gateway;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gateway.rate-limit")
public class RateLimitProperties {

    private boolean enabled = true;
    private String keyPrefix = "api-gateway:rate-limit:";
    private Limit defaultLimit = new Limit();
    private Map<String, Limit> clientLimits = new HashMap<>();
    private Duration tierCacheTtl = Duration.ofSeconds(30);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    public Limit getDefaultLimit() {
        return defaultLimit;
    }

    public void setDefaultLimit(Limit defaultLimit) {
        this.defaultLimit = defaultLimit;
    }

    public Map<String, Limit> getClientLimits() {
        return clientLimits;
    }

    public void setClientLimits(Map<String, Limit> clientLimits) {
        this.clientLimits = clientLimits;
    }

    public Limit limitFor(String clientId) {
        return clientLimits.getOrDefault(clientId, defaultLimit);
    }

    public Duration getTierCacheTtl() {
        return tierCacheTtl;
    }

    public void setTierCacheTtl(Duration tierCacheTtl) {
        this.tierCacheTtl = tierCacheTtl;
    }

    public void validate() {
        if (keyPrefix == null || keyPrefix.isBlank()) {
            throw new IllegalArgumentException("Rate-limit key prefix must not be blank");
        }
        if (tierCacheTtl == null || tierCacheTtl.isZero() || tierCacheTtl.isNegative()) {
            throw new IllegalArgumentException("Rate-limit tier-cache-ttl must be positive");
        }
        defaultLimit.validate("default-limit");
        clientLimits.forEach((clientId, limit) -> {
            if (clientId == null || clientId.isBlank()) {
                throw new IllegalArgumentException("Rate-limit client IDs must not be blank");
            }
            limit.validate("client-limits." + clientId);
        });
    }

    public static class Limit {

        private int capacity = 10;
        private int refillTokens = 10;
        private Duration refillPeriod = Duration.ofSeconds(1);

        public int getCapacity() {
            return capacity;
        }

        public void setCapacity(int capacity) {
            this.capacity = capacity;
        }

        public int getRefillTokens() {
            return refillTokens;
        }

        public void setRefillTokens(int refillTokens) {
            this.refillTokens = refillTokens;
        }

        public Duration getRefillPeriod() {
            return refillPeriod;
        }

        public void setRefillPeriod(Duration refillPeriod) {
            this.refillPeriod = refillPeriod;
        }

        private void validate(String name) {
            if (capacity <= 0 || refillTokens <= 0 || refillPeriod == null || refillPeriod.toMillis() <= 0) {
                throw new IllegalArgumentException(
                        name + " requires positive capacity, refill-tokens, and refill-period");
            }
        }
    }
}