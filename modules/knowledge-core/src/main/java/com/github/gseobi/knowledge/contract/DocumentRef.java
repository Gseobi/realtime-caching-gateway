package com.github.gseobi.knowledge.contract;

import com.github.gseobi.foundation.identity.TenantId;
import java.util.Objects;

/** Versions are durable, monotonic generations, including tombstones and ACL changes. */
public record DocumentRef(TenantId tenantId, String documentId, long version, long accessRevision) {
    public DocumentRef {
        Objects.requireNonNull(tenantId, "tenantId");
        if (documentId == null || documentId.isBlank()) throw new IllegalArgumentException("documentId is required");
        if (version < 1 || accessRevision < 1) throw new IllegalArgumentException("versions must be positive");
    }
}
