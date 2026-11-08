CREATE TABLE IF NOT EXISTS crm_customer (
    tenant_id VARCHAR(64) NOT NULL,
    customer_id VARCHAR(64) NOT NULL,
    owner_agent_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (tenant_id, customer_id)
);
CREATE TABLE IF NOT EXISTS crm_contact (
    tenant_id VARCHAR(64) NOT NULL,
    contact_id VARCHAR(64) NOT NULL,
    customer_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (tenant_id, contact_id),
    FOREIGN KEY (tenant_id, customer_id) REFERENCES crm_customer (tenant_id, customer_id)
);
CREATE TABLE IF NOT EXISTS crm_conversation (
    tenant_id VARCHAR(64) NOT NULL,
    conversation_id VARCHAR(64) NOT NULL,
    contact_id VARCHAR(64) NOT NULL,
    agent_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (tenant_id, conversation_id),
    FOREIGN KEY (tenant_id, contact_id) REFERENCES crm_contact (tenant_id, contact_id)
);
CREATE TABLE IF NOT EXISTS crm_conversation_participant (
    tenant_id VARCHAR(64) NOT NULL,
    conversation_id VARCHAR(64) NOT NULL,
    principal_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (tenant_id, conversation_id, principal_id),
    FOREIGN KEY (tenant_id, conversation_id) REFERENCES crm_conversation (tenant_id, conversation_id)
);
