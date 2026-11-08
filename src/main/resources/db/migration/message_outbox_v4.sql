CREATE TABLE IF NOT EXISTS message_outbox (
    tenant_id VARCHAR(64) NOT NULL,
    event_id UUID NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    aggregate_version BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    schema_version INTEGER NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    payload_json JSONB NOT NULL,
    available_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    attempts INTEGER NOT NULL DEFAULT 0,
    delivered_at TIMESTAMPTZ,
    last_error VARCHAR(64),
    PRIMARY KEY (tenant_id, event_id)
);
CREATE INDEX IF NOT EXISTS idx_message_outbox_due
    ON message_outbox (tenant_id, available_at, event_id) WHERE delivered_at IS NULL;

CREATE TABLE IF NOT EXISTS message_event_inbox (
    tenant_id VARCHAR(64) NOT NULL,
    event_id UUID NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
    PRIMARY KEY (tenant_id, event_id),
    FOREIGN KEY (tenant_id, event_id) REFERENCES message_outbox (tenant_id, event_id)
);
