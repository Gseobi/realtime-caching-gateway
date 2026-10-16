package com.github.gseobi.cachinggateway.port;

import com.github.gseobi.cachinggateway.domain.message.model.MessageCacheData;

/** Best-effort live notification, not durable delivery. */

public interface MessageEventPublisher {
    void publish(MessageCacheData message);
}
