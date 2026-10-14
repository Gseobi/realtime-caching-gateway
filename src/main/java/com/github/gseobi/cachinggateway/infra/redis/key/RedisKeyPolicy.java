package com.github.gseobi.cachinggateway.infra.redis.key;

public final class RedisKeyPolicy {

    private RedisKeyPolicy() {}

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
