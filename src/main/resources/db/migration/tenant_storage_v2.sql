-- Empty tenant_id preserves existing data and the legacy API partition.
ALTER TABLE message ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT '';
ALTER TABLE conversation_state ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT '';

ALTER TABLE message DROP CONSTRAINT IF EXISTS message_pkey,
    DROP CONSTRAINT IF EXISTS message_tenant_pkey,
    ADD CONSTRAINT message_tenant_pkey PRIMARY KEY (tenant_id, message_id);
ALTER TABLE conversation_state DROP CONSTRAINT IF EXISTS conversation_state_pkey,
    DROP CONSTRAINT IF EXISTS conversation_state_tenant_pkey,
    ADD CONSTRAINT conversation_state_tenant_pkey PRIMARY KEY (tenant_id, conversation_id);

CREATE INDEX IF NOT EXISTS idx_message_tenant_page
    ON message (tenant_id, conversation_id, sent_at DESC, message_id DESC);
