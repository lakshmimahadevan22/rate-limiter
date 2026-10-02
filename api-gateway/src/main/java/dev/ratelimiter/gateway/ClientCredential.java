package dev.ratelimiter.gateway;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * {@code clientId} is client-assigned, not database-generated, so without {@link Persistable}
 * Spring Data R2DBC sees a non-null id and assumes an UPDATE is needed even for a brand-new row —
 * issuing an UPDATE that matches zero rows and fails. The persistence constructor (used only to
 * reconstruct rows already read from the database) marks the instance as not new; {@link #createNew}
 * is the only way application code should obtain a genuinely new, unsaved instance.
 */
@Table("clients")
public class ClientCredential implements Persistable<String> {

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

    @Transient
    private boolean isNew;

    public ClientCredential() {
    }

    @PersistenceCreator
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
        this.isNew = false;
    }

    /** The only way to obtain an unsaved instance that {@code save()} will INSERT rather than UPDATE. */
    public static ClientCredential createNew(
            String clientId,
            String apiKeyHash,
            int rateLimitCapacity,
            int rateLimitRefillTokens,
            long rateLimitRefillPeriodMillis,
            boolean enabled,
            Instant createdAt,
            Instant updatedAt) {
        ClientCredential credential = new ClientCredential(
                clientId, apiKeyHash, rateLimitCapacity, rateLimitRefillTokens,
                rateLimitRefillPeriodMillis, enabled, createdAt, updatedAt);
        credential.isNew = true;
        return credential;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @Override
    public String getId() {
        return clientId;
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
