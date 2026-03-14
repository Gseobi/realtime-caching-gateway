CREATE TABLE IF NOT EXISTS conversation (
    conversation_id VARCHAR(64) PRIMARY KEY,
    conversation_type VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS conversation_participant (
    conversation_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    joined_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (conversation_id, user_id)
);

CREATE TABLE IF NOT EXISTS message (
    message_id VARCHAR(64) PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL,
    sender_id VARCHAR(64) NOT NULL,
    message_type VARCHAR(20) NOT NULL,
    content TEXT,
    metadata_json TEXT,
    sent_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS conversation_state (
    conversation_id VARCHAR(64) PRIMARY KEY,
    last_message_id VARCHAR(64),
    last_message_preview TEXT,
    last_sender_id VARCHAR(64),
    last_sent_at TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_message_conversation_sent_at ON message (conversation_id, sent_at DESC);

CREATE INDEX IF NOT EXISTS idx_message_sender_id ON message (sender_id);


