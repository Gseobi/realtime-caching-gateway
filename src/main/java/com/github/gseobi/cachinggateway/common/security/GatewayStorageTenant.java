package com.github.gseobi.cachinggateway.common.security;

import com.github.gseobi.cachinggateway.StorageTenant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
public class GatewayStorageTenant {
    private final boolean sharedStorage;

    public GatewayStorageTenant(@Value("${gateway.auth.shared-storage:false}") boolean sharedStorage) {
        this.sharedStorage = sharedStorage;
    }

    public StorageTenant resolve(Jwt authenticatedJwt) {
        if (!sharedStorage) return StorageTenant.LEGACY;
        if (authenticatedJwt == null || !(authenticatedJwt.getClaims().get("tenant_id") instanceof String id)
                || id.isBlank()) {
            throw new IllegalStateException("Shared storage requires an authenticated tenant");
        }
        return new StorageTenant(id);
    }
}
