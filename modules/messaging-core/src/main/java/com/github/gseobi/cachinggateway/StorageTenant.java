package com.github.gseobi.cachinggateway;

/** Explicit storage partition; the empty ID is reserved for pre-tenant data. */
public record StorageTenant(String id) {
    public static final StorageTenant LEGACY = new StorageTenant("");

    public StorageTenant {
        if (id == null || (!id.isEmpty() && !id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))) {
            throw new IllegalArgumentException("Invalid storage tenant");
        }
    }
}
