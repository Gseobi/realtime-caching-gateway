package com.github.gseobi.crm.contract;

import com.github.gseobi.foundation.identity.TenantId;
import java.util.Objects;

/** Durable uniqueness is (tenant, operation, key), not a global cache key.
 * A future handler must hash a canonical payload internally and commit receipt + mutation atomically. */
public record IdempotencyKey(TenantId tenantId, String operation, String key) {
    public IdempotencyKey {
        Objects.requireNonNull(tenantId, "tenantId");
        if (operation == null || operation.isBlank()) throw new IllegalArgumentException("operation is required");
        if (key == null || key.isBlank()) throw new IllegalArgumentException("key is required");
    }
}
