package com.github.gseobi.cachinggateway.infra.redis.key;

import com.github.gseobi.cachinggateway.StorageTenant;

public final class RedisKeyPolicy {

    private RedisKeyPolicy() {}

    private static String prefix(StorageTenant tenant) {
        return tenant.id().isEmpty() ? "" : "tenant:" + tenant.id() + ":";
    }

    public static String conversationMeta(StorageTenant tenant, String id) {
        return prefix(tenant) + conversationMeta(id);
    }

    public static String conversationMessageIndex(StorageTenant tenant, String id) {
        return prefix(tenant) + conversationMessageIndex(id);
    }

    public static String message(StorageTenant tenant, String id) {
        return prefix(tenant) + message(id);
    }

    public static String pubsubMessageIn(StorageTenant tenant) {
        return prefix(tenant) + pubsubMessageIn();
    }

    public static String conversationMeta(String conversationId) {
        return "v2:conv:" + conversationId + ":meta";
    }

    public static String conversationMessageIndex(String conversationId) {
        return "v2:conv:" + conversationId + ":message_index";
    }

    public static String message(String messageId) {
        return "v2:msg:" + messageId;
    }

    public static String pubsubMessageIn() {
        return "pubsub:message:in";
    }
}
