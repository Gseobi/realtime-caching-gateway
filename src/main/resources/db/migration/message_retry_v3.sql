CREATE TABLE IF NOT EXISTS message_retry (
    tenant_id VARCHAR(64) NOT NULL,
    retry_key VARCHAR(128) NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    PRIMARY KEY (tenant_id, retry_key)
);
