package com.github.gseobi.cachinggateway.infra.redis.key;

public final class RedisKeyPolicy {

    private RedisKeyPolicy() {}

    public static String conversationMeta(String conversationId) {
        return "conv:" + conversationId + ":meta";
    }

    public static String conversationMessageIndex(String conversationId) {
        return "conv:" + conversationId + ":message_index";
    }

    public static String message(String messageId) {
        return "msg:" + messageId;
    }

    public static String dirtyConversations() {
        return "sync:dirty:conversations";
    }

    public static String pubsubMessageIn() {
        return "pubsub:message:in";
    }
}
