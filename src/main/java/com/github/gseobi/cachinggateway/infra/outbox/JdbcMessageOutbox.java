package com.github.gseobi.cachinggateway.infra.outbox;

import com.github.gseobi.crm.contract.OutboxEvent;
import com.github.gseobi.crm.contract.OutboxPort;
import com.github.gseobi.foundation.identity.RequestContext;
import com.github.gseobi.foundation.identity.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcMessageOutbox implements OutboxPort {
    private static final String ACTIVE_LEASE = """
            WHERE tenant_id = ? AND event_id = ? AND lease_token = ?
              AND delivered_at IS NULL AND lease_until > statement_timestamp()
            """;
    private final JdbcTemplate jdbc;

    public JdbcMessageOutbox(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(RequestContext context, OutboxEvent event) {
        context.requireTenant(event.tenantId());
        jdbc.update("""
                INSERT INTO message_outbox (tenant_id, event_id, aggregate_id, aggregate_version,
                    event_type, schema_version, occurred_at, payload_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                """, event.tenantId().value(), event.eventId(), event.aggregateId(), event.aggregateVersion(),
                event.eventType(), event.schemaVersion(), utc(event.occurredAt()), event.payloadJson());
    }

    @Override
    @Transactional
    public List<Lease> claim(RequestContext worker, int limit, Duration leaseDuration) {
        if (limit < 1 || limit > 100 || leaseDuration.compareTo(Duration.ofSeconds(1)) < 0
                || leaseDuration.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException("Invalid outbox claim bounds");
        }
        return jdbc.query("""
                WITH due AS (
                    SELECT tenant_id, event_id FROM message_outbox
                    WHERE tenant_id = ? AND delivered_at IS NULL AND available_at <= statement_timestamp()
                      AND (lease_until IS NULL OR lease_until <= statement_timestamp())
                    ORDER BY available_at, event_id
                    LIMIT ? FOR UPDATE SKIP LOCKED
                )
                UPDATE message_outbox o
                SET lease_token = ?, lease_until = statement_timestamp() + (? * interval '1 millisecond'),
                    attempts = attempts + 1
                FROM due
                WHERE o.tenant_id = due.tenant_id AND o.event_id = due.event_id
                RETURNING o.*
                """, (rs, row) -> new Lease(new OutboxEvent(rs.getObject("event_id", UUID.class),
                        new TenantId(rs.getString("tenant_id")), rs.getString("aggregate_id"),
                        rs.getLong("aggregate_version"), rs.getString("event_type"), rs.getInt("schema_version"),
                        rs.getObject("occurred_at", OffsetDateTime.class).toInstant(), rs.getString("payload_json")),
                        rs.getObject("lease_token", UUID.class),
                        rs.getObject("lease_until", OffsetDateTime.class).toInstant(), rs.getInt("attempts")),
                worker.tenantId().value(), limit, UUID.randomUUID(), leaseDuration.toMillis());
    }

    @Override
    public boolean complete(RequestContext worker, Lease lease) {
        worker.requireTenant(lease.event().tenantId());
        return jdbc.update("""
                UPDATE message_outbox SET delivered_at = statement_timestamp(), lease_token = NULL, lease_until = NULL
                """ + ACTIVE_LEASE, worker.tenantId().value(), lease.event().eventId(), lease.token()) == 1;
    }

    @Override
    public boolean retry(RequestContext worker, Lease lease, Instant nextAttempt, String errorCode) {
        worker.requireTenant(lease.event().tenantId());
        return jdbc.update("""
                UPDATE message_outbox SET available_at = ?, last_error = ?, lease_token = NULL, lease_until = NULL
                """ + ACTIVE_LEASE, utc(nextAttempt), errorCode, worker.tenantId().value(), lease.event().eventId(),
                lease.token()) == 1;
    }

    /** Move a claimed event into the durable local inbox, atomically with completion. */
    @Transactional
    public boolean deliverToInbox(RequestContext worker, Lease lease) {
        if (!complete(worker, lease)) return false;
        jdbc.update("""
                INSERT INTO message_event_inbox (tenant_id, event_id) VALUES (?, ?)
                ON CONFLICT (tenant_id, event_id) DO NOTHING
                """, worker.tenantId().value(), lease.event().eventId());
        return true;
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
