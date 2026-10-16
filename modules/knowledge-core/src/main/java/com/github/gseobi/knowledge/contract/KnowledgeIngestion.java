package com.github.gseobi.knowledge.contract;

import com.github.gseobi.foundation.identity.RequestContext;
import java.net.URI;
import java.util.UUID;

/** Durable job scaffold. Implementations need tenant authorization, idempotent enqueue and fenced workers.
 * Source URIs require an allowlisted fetch adapter; never fetch arbitrary URLs from this contract. */
public interface KnowledgeIngestion {
    UUID enqueue(RequestContext context, DocumentRef document, URI source, IndexProfile index);
    /** Persist the tombstone before asynchronous index removal; stale jobs must not resurrect this generation. */
    UUID delete(RequestContext context, DocumentRef tombstone);
    JobStatus status(RequestContext context, UUID jobId);

    enum JobStatus { PENDING, INDEXING, READY, FAILED, DELETE_PENDING, DELETED }
}
