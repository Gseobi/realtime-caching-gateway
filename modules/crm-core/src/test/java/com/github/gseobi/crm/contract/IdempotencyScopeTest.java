package com.github.gseobi.crm.contract;

import com.github.gseobi.foundation.identity.TenantId;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IdempotencyScopeTest {
    @Test void identicalClientKeyDoesNotCollideAcrossTenantsOrOperations() {
        var key = new IdempotencyKey(new TenantId("tenant-a"), "customer.create", "client-key");
        assertNotEquals(key, new IdempotencyKey(new TenantId("tenant-b"), "customer.create", "client-key"));
        assertNotEquals(key, new IdempotencyKey(new TenantId("tenant-a"), "message.create", "client-key"));
        assertEquals(key, new IdempotencyKey(new TenantId("tenant-a"), "customer.create", "client-key"));
    }
}
