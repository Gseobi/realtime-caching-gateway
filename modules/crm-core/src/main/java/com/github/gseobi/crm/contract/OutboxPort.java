package com.github.gseobi.crm.contract;

import com.github.gseobi.foundation.identity.RequestContext;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Unimplemented durable adapter contract. No Pub/Sub implementation can satisfy this port. */
public interface OutboxPort {
    /** Must join the business mutation's transaction; reject a call without that transaction. */
    void append(RequestContext context, OutboxEvent event);
    /** Atomically claim due events for the authorized worker's tenant; use DB time for lease expiry. */
    List<Lease> claim(RequestContext worker, int limit, Duration leaseDuration);
    /** Compare tenant, event ID and token; reject expired/stale leases. */
    boolean complete(RequestContext worker, Lease lease);
    boolean retry(RequestContext worker, Lease lease, Instant nextAttempt, String errorCode);

    record Lease(OutboxEvent event, UUID token, Instant expiresAt, int attempt) {
        public Lease {
            Objects.requireNonNull(event, "event");
            Objects.requireNonNull(token, "token");
            Objects.requireNonNull(expiresAt, "expiresAt");
            if (attempt < 1) throw new IllegalArgumentException("attempt must be positive");
        }
    }
}
