package com.github.gseobi.crm.contract;

import com.github.gseobi.foundation.identity.TenantId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record OutboxEvent(UUID eventId, TenantId tenantId, String aggregateId, long aggregateVersion,
                          String eventType, int schemaVersion, Instant occurredAt, String payloadJson) {
    public OutboxEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (aggregateId == null || aggregateId.isBlank() || eventType == null || eventType.isBlank())
            throw new IllegalArgumentException("aggregateId and eventType are required");
        if (aggregateVersion < 1 || schemaVersion < 1) throw new IllegalArgumentException("versions must be positive");
        if (payloadJson == null || payloadJson.isBlank()) throw new IllegalArgumentException("payload is required");
    }
}
