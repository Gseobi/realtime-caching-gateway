CREATE TABLE IF NOT EXISTS knowledge_document (
    tenant_id VARCHAR(64) NOT NULL,
    document_id UUID NOT NULL,
    owner_id VARCHAR(64) NOT NULL,
    source_label VARCHAR(500) NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    body TEXT,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (tenant_id, document_id),
    CHECK (deleted = (body IS NULL))
);
CREATE TABLE IF NOT EXISTS knowledge_reader (
    tenant_id VARCHAR(64) NOT NULL,
    document_id UUID NOT NULL,
    principal_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (tenant_id, document_id, principal_id),
    FOREIGN KEY (tenant_id, document_id) REFERENCES knowledge_document (tenant_id, document_id)
);
CREATE TABLE IF NOT EXISTS knowledge_chunk (
    tenant_id VARCHAR(64) NOT NULL,
    document_id UUID NOT NULL,
    revision BIGINT NOT NULL,
    ordinal INTEGER NOT NULL,
    body TEXT NOT NULL,
    search_vector TSVECTOR GENERATED ALWAYS AS (to_tsvector('simple', body)) STORED,
    PRIMARY KEY (tenant_id, document_id, revision, ordinal),
    FOREIGN KEY (tenant_id, document_id) REFERENCES knowledge_document (tenant_id, document_id)
);
CREATE INDEX IF NOT EXISTS idx_knowledge_chunk_search ON knowledge_chunk USING GIN (search_vector);

CREATE TABLE IF NOT EXISTS knowledge_job (
    tenant_id VARCHAR(64) NOT NULL,
    job_id UUID NOT NULL,
    document_id UUID NOT NULL,
    revision BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('READY','DELETED')),
    PRIMARY KEY (tenant_id, job_id),
    FOREIGN KEY (tenant_id, document_id) REFERENCES knowledge_document (tenant_id, document_id)
);
