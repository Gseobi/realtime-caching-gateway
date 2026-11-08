package com.github.gseobi.cachinggateway.domain.knowledge;

import com.github.gseobi.cachinggateway.common.exception.ResourceNotFoundException;
import com.github.gseobi.crm.contract.OutboxEvent;
import com.github.gseobi.crm.contract.OutboxPort;
import com.github.gseobi.foundation.identity.RequestContext;
import com.github.gseobi.knowledge.contract.LocalKnowledge;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PostgresLocalKnowledge implements LocalKnowledge {
    private final JdbcTemplate jdbc;
    private final OutboxPort outbox;

    public PostgresLocalKnowledge(JdbcTemplate jdbc, OutboxPort outbox) {
        this.jdbc = jdbc;
        this.outbox = outbox;
    }

    @Override @Transactional
    public Job create(RequestContext ctx, Document input) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO knowledge_document (tenant_id, document_id, owner_id, source_label, revision, body)
                VALUES (?, ?, ?, ?, 1, ?)
                """, ctx.tenantId().value(), id, ctx.principalId(), input.source(), input.text());
        return index(ctx, id, 1, input);
    }

    @Override @Transactional
    public Job replace(RequestContext ctx, UUID id, long expected, Document input) {
        lockCurrent(ctx, id, expected);
        long revision = Math.incrementExact(expected);
        jdbc.update("""
                UPDATE knowledge_document SET source_label = ?, body = ?, revision = ?
                WHERE tenant_id = ? AND document_id = ?
                """, input.source(), input.text(), revision, ctx.tenantId().value(), id);
        return index(ctx, id, revision, input);
    }

    @Override @Transactional
    public Job rebuild(RequestContext ctx, UUID id, long expected) {
        String body = lockCurrent(ctx, id, expected);
        replaceChunks(ctx, id, expected, body);
        return record(ctx, id, expected, "READY", null);
    }

    @Override @Transactional
    public Job delete(RequestContext ctx, UUID id, long expected) {
        lockCurrent(ctx, id, expected);
        long revision = Math.incrementExact(expected);
        jdbc.update("""
                UPDATE knowledge_document SET body = NULL, deleted = TRUE, revision = ?
                WHERE tenant_id = ? AND document_id = ?
                """, revision, ctx.tenantId().value(), id);
        jdbc.update("DELETE FROM knowledge_chunk WHERE tenant_id = ? AND document_id = ?", ctx.tenantId().value(), id);
        jdbc.update("DELETE FROM knowledge_reader WHERE tenant_id = ? AND document_id = ?", ctx.tenantId().value(), id);
        return record(ctx, id, revision, "DELETED", "knowledge.deleted");
    }

    @Override
    public Optional<Job> job(RequestContext ctx, UUID jobId) {
        return jdbc.query("""
                SELECT j.document_id, j.revision, j.status FROM knowledge_job j
                JOIN knowledge_document d USING (tenant_id, document_id)
                WHERE j.tenant_id = ? AND j.job_id = ? AND d.owner_id = ?
                """, (rs, row) -> new Job(jobId, rs.getObject("document_id", UUID.class),
                rs.getLong("revision"), rs.getString("status")), ctx.tenantId().value(), jobId, ctx.principalId())
                .stream().findFirst();
    }

    @Override
    public List<Hit> search(RequestContext ctx, String query, int limit) {
        if (query == null || query.isBlank() || query.length() > 512 || limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Invalid knowledge search bounds");
        }
        return jdbc.query("""
                WITH query AS (SELECT plainto_tsquery('simple', ?) AS value)
                SELECT c.document_id, c.revision, c.ordinal, d.source_label, c.body
                FROM knowledge_chunk c JOIN knowledge_document d USING (tenant_id, document_id)
                CROSS JOIN query q
                WHERE c.tenant_id = ? AND NOT d.deleted AND c.revision = d.revision
                  AND c.search_vector @@ q.value
                  AND (d.owner_id = ? OR EXISTS (
                    SELECT 1 FROM knowledge_reader r
                    WHERE r.tenant_id = c.tenant_id AND r.document_id = c.document_id AND r.principal_id = ?
                  ))
                ORDER BY ts_rank(c.search_vector, q.value) DESC, c.document_id, c.ordinal
                LIMIT ?
                """, (rs, row) -> new Hit(rs.getObject("document_id", UUID.class), rs.getLong("revision"),
                rs.getInt("ordinal"), rs.getString("source_label"), rs.getString("body")),
                query, ctx.tenantId().value(), ctx.principalId(), ctx.principalId(), limit);
    }

    private String lockCurrent(RequestContext ctx, UUID id, long expected) {
        return jdbc.query("""
                SELECT revision, body FROM knowledge_document
                WHERE tenant_id = ? AND document_id = ? AND owner_id = ? AND NOT deleted
                FOR UPDATE
                """, (rs, row) -> {
                    if (rs.getLong("revision") != expected) {
                        throw new OptimisticLockingFailureException("Document revision changed");
                    }
                    return rs.getString("body");
                }, ctx.tenantId().value(), id, ctx.principalId()).stream().findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Document not found"));
    }

    private Job index(RequestContext ctx, UUID id, long revision, Document input) {
        jdbc.update("DELETE FROM knowledge_reader WHERE tenant_id = ? AND document_id = ?", ctx.tenantId().value(), id);
        for (String reader : input.readers()) {
            jdbc.update("INSERT INTO knowledge_reader VALUES (?, ?, ?)", ctx.tenantId().value(), id, reader);
        }
        replaceChunks(ctx, id, revision, input.text());
        return record(ctx, id, revision, "READY", "knowledge.indexed");
    }

    private void replaceChunks(RequestContext ctx, UUID id, long revision, String text) {
        jdbc.update("DELETE FROM knowledge_chunk WHERE tenant_id = ? AND document_id = ?", ctx.tenantId().value(), id);
        int start = 0;
        int ordinal = 0;
        int remaining = text.codePointCount(0, text.length());
        while (remaining > 0) {
            int count = Math.min(remaining, 2000);
            int end = text.offsetByCodePoints(start, count);
            String chunk = text.substring(start, end);
            if (!chunk.isBlank()) {
                jdbc.update("""
                        INSERT INTO knowledge_chunk (tenant_id, document_id, revision, ordinal, body)
                        VALUES (?, ?, ?, ?, ?)
                        """, ctx.tenantId().value(), id, revision, ordinal++, chunk);
            }
            remaining -= count;
            start = end;
        }
    }

    private Job record(RequestContext ctx, UUID id, long revision, String status, String eventType) {
        var jobId = UUID.randomUUID();
        jdbc.update("INSERT INTO knowledge_job VALUES (?, ?, ?, ?, ?)",
                ctx.tenantId().value(), jobId, id, revision, status);
        if (eventType != null) {
            outbox.append(ctx, new OutboxEvent(jobId, ctx.tenantId(), id.toString(), revision,
                    eventType, 1, Instant.now(), "{}"));
        }
        return new Job(jobId, id, revision, status);
    }
}
