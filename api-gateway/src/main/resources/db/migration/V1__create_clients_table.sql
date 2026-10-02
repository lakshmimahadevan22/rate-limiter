CREATE TABLE clients (
    client_id VARCHAR(100) PRIMARY KEY,
    api_key_hash VARCHAR(200) NOT NULL,
    rate_limit_capacity INTEGER NOT NULL,
    rate_limit_refill_tokens INTEGER NOT NULL,
    rate_limit_refill_period_ms BIGINT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
