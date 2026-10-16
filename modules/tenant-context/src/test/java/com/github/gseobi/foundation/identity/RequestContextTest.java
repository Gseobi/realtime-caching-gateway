package com.github.gseobi.foundation.identity;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RequestContextTest {
    @Test void rejectsMissingIdentityRatherThanChoosingDefaultTenant() {
        assertThrows(IllegalArgumentException.class, () -> new TenantId(" "));
        assertThrows(IllegalArgumentException.class, () -> new RequestContext(new TenantId("t"), "", "request"));
        assertThrows(NullPointerException.class, () -> new RequestContext(null, "user", "request"));
    }
    @Test void resourceTenantMustMatchExplicitRequestTenant() {
        var context = new RequestContext(new TenantId("t"), "user", "request");
        assertDoesNotThrow(() -> context.requireTenant(new TenantId("t")));
        assertThrows(SecurityException.class, () -> context.requireTenant(new TenantId("other")));
        assertThrows(SecurityException.class, () -> context.requireTenant(null));
    }
}
