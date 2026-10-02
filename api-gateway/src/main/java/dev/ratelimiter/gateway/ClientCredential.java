package dev.ratelimiter.gateway;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("clients")
public class ClientCredential {

    @Id
    @Column("client_id")
    private String clientId;

    @Column("api_key_hash")
    private String apiKeyHash;

    @Column("rate_limit_capacity")
    private int rateLimitCapacity;

    @Column("rate_limit_refill_tokens")
    private int rateLimitRefillTokens;

    @Column("rate_limit_refill_period_ms")
    private long rateLimitRefillPeriodMillis;

    @Column("enabled")
    private boolean enabled;

    @Column("created_at")
    private Instant createdAt;

    @Column("updated_at")
    private Instant updatedAt;

    public ClientCredential() {
    }

    public ClientCredential(
            String clientId,
            String apiKeyHash,
            int rateLimitCapacity,
            int rateLimitRefillTokens,
            long rateLimitRefillPeriodMillis,
            boolean enabled,
            Instant createdAt,
            Instant updatedAt) {
        this.clientId = clientId;
        this.apiKeyHash = apiKeyHash;
        this.rateLimitCapacity = rateLimitCapacity;
        this.rateLimitRefillTokens = rateLimitRefillTokens;
        this.rateLimitRefillPeriodMillis = rateLimitRefillPeriodMillis;
        this.enabled = enabled;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getApiKeyHash() {
        return apiKeyHash;
    }

    public void setApiKeyHash(String apiKeyHash) {
        this.apiKeyHash = apiKeyHash;
    }

    public int getRateLimitCapacity() {
        return rateLimitCapacity;
    }

    public void setRateLimitCapacity(int rateLimitCapacity) {
        this.rateLimitCapacity = rateLimitCapacity;
    }

    public int getRateLimitRefillTokens() {
        return rateLimitRefillTokens;
    }

    public void setRateLimitRefillTokens(int rateLimitRefillTokens) {
        this.rateLimitRefillTokens = rateLimitRefillTokens;
    }

    public long getRateLimitRefillPeriodMillis() {
        return rateLimitRefillPeriodMillis;
    }

    public void setRateLimitRefillPeriodMillis(long rateLimitRefillPeriodMillis) {
        this.rateLimitRefillPeriodMillis = rateLimitRefillPeriodMillis;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
