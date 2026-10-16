package com.github.gseobi.crm.contract;

import com.github.gseobi.foundation.identity.TenantId;
import java.util.Objects;

/** Minimal identity scaffold; contact PII and ownership policy are deliberately unspecified. */
public record Customer(TenantId tenantId, String customerId, long version) {
    public Customer {
        Objects.requireNonNull(tenantId, "tenantId");
        if (customerId == null || customerId.isBlank()) throw new IllegalArgumentException("customerId is required");
        if (version < 1) throw new IllegalArgumentException("version must be positive");
    }
}
