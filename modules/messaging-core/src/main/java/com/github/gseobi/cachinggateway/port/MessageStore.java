package com.github.gseobi.cachinggateway.port;

import com.github.gseobi.cachinggateway.domain.message.model.MessageCacheData;
import java.time.LocalDateTime;
import java.util.List;

/** Durable operations; the application service owns the transaction boundary. */

public interface MessageStore {
    List<MessageCacheData> findMessages(String conversationId, LocalDateTime before, LocalDateTime after, Integer limit);
    List<String> findMessageIds(String conversationId, LocalDateTime before, LocalDateTime after, Integer limit);
    void insertMessage(MessageCacheData message);
    void upsertConversationState(String conversationId, String messageId, String preview, String senderId, LocalDateTime sentAt);
}
