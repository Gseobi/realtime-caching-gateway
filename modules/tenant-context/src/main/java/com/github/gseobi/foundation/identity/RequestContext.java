package com.github.gseobi.foundation.identity;

import java.util.Objects;

/** Construct only after authentication and tenant membership checks in a trusted adapter.
 * This value carries identity, and does not itself authenticate or authorize a request. */
public record RequestContext(TenantId tenantId, String principalId, String requestId) {
    public RequestContext {
        Objects.requireNonNull(tenantId, "tenantId");
        if (principalId == null || principalId.isBlank()) throw new IllegalArgumentException("principalId is required");
        if (requestId == null || requestId.isBlank()) throw new IllegalArgumentException("requestId is required");
    }
    public void requireTenant(TenantId resourceTenant) {
        if (!tenantId.equals(resourceTenant)) throw new SecurityException("tenant mismatch");
    }
}
